package main

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	_ "github.com/lib/pq"
)

const workerCount = 50

type Character struct {
	ID           int64
	Name         string
	RefreshToken string
}

type EveTokenResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int    `json:"expires_in"`
}

var (
	aesKeyBase64 string
	eveClientID  string
	eveSecret    string
)

func main() {
	dbDSN := os.Getenv("DB_DSN")
	aesKeyBase64 = os.Getenv("KEYYY")
	eveClientID = os.Getenv("EVE_CLIENT_ID")
	eveSecret = os.Getenv("EVE_CLIENT_SECRET")

	log.Println("Starte EVE Token Sync Service...")

	// 1. Datenbankverbindung herstellen
	db, err := sql.Open("postgres", dbDSN)
	if err != nil {
		log.Fatalf("DB Verbindung fehlgeschlagen: %v", err)
	}
	defer db.Close()

	for {
		log.Println("Starte neuen Token-Refresh-Durchlauf...")

		rows, err := db.Query(`SELECT character_id, name, refresh_token FROM characters WHERE refresh_token IS NOT NULL`)
		if err != nil {
			log.Printf("Konnte Charaktere nicht laden (DB noch nicht bereit?): %v\n", err)
			time.Sleep(30 * time.Second)
			continue
		}

		var characters []Character
		for rows.Next() {
			var c Character
			if err := rows.Scan(&c.ID, &c.Name, &c.RefreshToken); err != nil {
				log.Println("Fehler beim Lesen einer Zeile:", err)
				continue
			}
			characters = append(characters, c)
		}
		rows.Close()

		if len(characters) > 0 {
			log.Printf("%d Charaktere gefunden. Starte Worker Pool mit %d Workern...\n", len(characters), workerCount)

			jobs := make(chan Character, len(characters))
			var wg sync.WaitGroup

			for w := 1; w <= workerCount; w++ {
				wg.Add(1)
				go worker(w, jobs, &wg, db)
			}

			for _, c := range characters {
				jobs <- c
			}
			close(jobs)

			wg.Wait()
			log.Println("Durchlauf komplett abgeschlossen.")
		} else {
			log.Println("Keine Charaktere für Token-Refresh gefunden.")
		}

		// Den Service schlafen legen.
		// Da Tokens 20 Minuten gültig sind, sind 5 Minuten Wartezeit absolut sicher.
		log.Println("Lege mich schlafen für 5 Minuten...")
		time.Sleep(5 * time.Minute)
	}
}

func worker(id int, jobs <-chan Character, wg *sync.WaitGroup, db *sql.DB) {
	defer wg.Done()

	client := &http.Client{Timeout: 10 * time.Second}
	aesKey, err := base64.StdEncoding.DecodeString(aesKeyBase64)
	if err != nil {
		log.Printf("[Worker %d] FATAL: Konnte AES Key nicht decodieren: %v\n", id, err)
		return
	}

	for c := range jobs {
		plainRefreshToken, err := decryptAES(c.RefreshToken, aesKey)
		if err != nil {
			log.Printf("[Worker %d] Fehler beim Entschlüsseln von %s: %v\n", id, c.Name, err)
			continue
		}

		data := url.Values{}
		data.Set("grant_type", "refresh_token")
		data.Set("refresh_token", plainRefreshToken)

		req, err := http.NewRequest("POST", "https://login.eveonline.com/v2/oauth/token", strings.NewReader(data.Encode()))
		if err != nil {
			continue
		}

		authHeader := base64.StdEncoding.EncodeToString([]byte(eveClientID + ":" + eveSecret))
		req.Header.Add("Authorization", "Basic "+authHeader)
		req.Header.Add("Content-Type", "application/x-www-form-urlencoded")

		resp, err := client.Do(req)
		if err != nil {
			log.Printf("[Worker %d] HTTP Fehler bei %s: %v\n", id, c.Name, err)
			continue
		}

		if resp.StatusCode == 400 || resp.StatusCode == 401 {
			log.Printf("[Worker %d] Token für %s (%d) ungültig (Code %d)! Lösche Token aus DB...\n", id, c.Name, c.ID, resp.StatusCode)
			db.Exec("UPDATE characters SET access_token = NULL, refresh_token = NULL WHERE character_id = $1", c.ID)
			resp.Body.Close()
			continue
		}

		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()

		var tokenResp EveTokenResponse
		if err := json.Unmarshal(body, &tokenResp); err != nil {
			log.Printf("[Worker %d] JSON Fehler bei %s: %v\n", id, c.Name, err)
			continue
		}

		encAccessToken, _ := encryptAES(tokenResp.AccessToken, aesKey)
		encRefreshToken := c.RefreshToken
		if tokenResp.RefreshToken != "" {
			encRefreshToken, _ = encryptAES(tokenResp.RefreshToken, aesKey)
		}

		expiry := time.Now().Add(time.Duration(tokenResp.ExpiresIn) * time.Second)

		_, err = db.Exec(`
			UPDATE characters 
			SET access_token = $1, refresh_token = $2, token_expiry = $3 
			WHERE character_id = $4`,
			encAccessToken, encRefreshToken, expiry, c.ID)

		if err != nil {
			log.Printf("[Worker %d] DB Update Fehler bei %s: %v\n", id, c.Name, err)
		} else {
			log.Printf("[Worker %d] Token für %s erfolgreich aktualisiert.\n", id, c.Name)
		}
	}
}

// =====================================================================
// AES KRYPTOGRAPHIE
// =====================================================================

func encryptAES(plainText string, key []byte) (string, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}

	plainBytes := pkcs5Pad([]byte(plainText), aes.BlockSize)
	iv := make([]byte, aes.BlockSize)
	if _, err := io.ReadFull(rand.Reader, iv); err != nil {
		return "", err
	}

	mode := cipher.NewCBCEncrypter(block, iv)
	encrypted := make([]byte, len(plainBytes))
	mode.CryptBlocks(encrypted, plainBytes)

	combined := append(iv, encrypted...)
	return base64.StdEncoding.EncodeToString(combined), nil
}

func decryptAES(encryptedBase64 string, key []byte) (string, error) {
	combined, err := base64.StdEncoding.DecodeString(encryptedBase64)
	if err != nil {
		return "", err
	}

	if len(combined) < aes.BlockSize {
		return "", fmt.Errorf("ciphertext too short")
	}

	iv := combined[:aes.BlockSize]
	encrypted := combined[aes.BlockSize:]

	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}

	mode := cipher.NewCBCDecrypter(block, iv)
	decrypted := make([]byte, len(encrypted))
	mode.CryptBlocks(decrypted, encrypted)

	return string(pkcs5Unpad(decrypted)), nil
}

func pkcs5Pad(src []byte, blockSize int) []byte {
	padding := blockSize - len(src)%blockSize
	padtext := bytes.Repeat([]byte{byte(padding)}, padding)
	return append(src, padtext...)
}

func pkcs5Unpad(src []byte) []byte {
	length := len(src)
	unpadding := int(src[length-1])
	return src[:(length - unpadding)]
}
