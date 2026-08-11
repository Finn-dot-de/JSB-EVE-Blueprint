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

	// Ein Netz darunter. Eine Panik in einer Goroutine beendet in Go den
	// GANZEN Prozess - nicht nur diese eine. Genau das ist passiert: ein
	// unbrauchbarer Token liess den Dienst in einer Neustartschleife haengen
	// und legte den Abgleich fuer alle Charaktere lahm. Ein einzelner
	// Datensatz darf hoechstens sich selbst kosten.
	defer func() {
		if r := recover(); r != nil {
			log.Printf("[Worker %d] Abgebrochen nach unerwartetem Fehler: %v\n", id, r)
		}
	}()

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
			grund, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			log.Printf("[Worker %d] Token fuer %s (%d) abgelehnt (Code %d) - vorgemerkt.", id, c.Name, c.ID, resp.StatusCode)

			// Vormerken statt loeschen.
			//
			// Frueher wurden hier access_token UND refresh_token auf NULL
			// gesetzt. Das ist unumkehrbar und im Zweifel falsch: ein 400 kommt
			// auch bei einer voruebergehenden Stoerung, und ein 401 betrifft die
			// ANWENDUNG, nicht den Charakter - bei falscher Client-ID oder
			// falschem Secret antwortet EVE fuer JEDEN Charakter mit 401, und
			// die alte Fassung haette in einem einzigen Durchlauf saemtliche
			// Tokens vernichtet. Danach muesste sich die ganze Corp neu
			// anmelden, wegen eines Tippfehlers in einer Umgebungsvariablen.
			//
			// Der Vermerk kostet nichts und ist umkehrbar: geht es beim
			// naechsten Durchlauf wieder, loescht der Erfolgspfad ihn von selbst.
			// COALESCE haelt den Zeitpunkt des ERSTEN Fehlschlags fest - sonst
			// hiesse es alle fuenf Minuten "seit gerade eben".
			_, markErr := db.Exec(`
				UPDATE characters
				SET token_invalid_since = COALESCE(token_invalid_since, now()),
				    token_invalid_reason = LEFT($2, 240)
				WHERE character_id = $1`,
				c.ID, fmt.Sprintf("HTTP %d: %s", resp.StatusCode, string(grund)))
			if markErr != nil {
				log.Printf("[Worker %d] Vermerk fuer %s nicht gespeichert: %v", id, c.Name, markErr)
			}
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

		// Der erfolgreiche Refresh loescht zugleich die Abmelde-Marke.
		//
		// Dieser Dienst haelt die Tokens am Leben - alle fuenf Minuten. Der
		// Java-Teil kommt deshalb praktisch nie dazu, selbst zu erneuern, und
		// nur dort wurde bisher entwarnt. Ohne diese drei Spalten bliebe die
		// Marke stehen, nachdem sich der Spieler laengst neu angemeldet hat,
		// und das Auth zeigte ihm weiter einen Hinweis, den er nicht loswird.
		_, err = db.Exec(`
			UPDATE characters 
			SET access_token = $1, refresh_token = $2, token_expiry = $3,
			    token_invalid_since = NULL,
			    token_invalid_reason = NULL,
			    token_invalid_notified_at = NULL
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

	// CryptBlocks paniert bei einer Laenge, die kein Vielfaches der Blockgroesse
	// ist. Lieber hier ein Fehler als ein Absturz des ganzen Dienstes.
	if len(encrypted) == 0 || len(encrypted)%aes.BlockSize != 0 {
		return "", fmt.Errorf("ciphertext ist kein Vielfaches der Blockgroesse (%d Bytes)", len(encrypted))
	}

	mode := cipher.NewCBCDecrypter(block, iv)
	decrypted := make([]byte, len(encrypted))
	mode.CryptBlocks(decrypted, encrypted)

	unpadded, err := pkcs5Unpad(decrypted)
	if err != nil {
		return "", err
	}
	return string(unpadded), nil
}

func pkcs5Pad(src []byte, blockSize int) []byte {
	padding := blockSize - len(src)%blockSize
	padtext := bytes.Repeat([]byte{byte(padding)}, padding)
	return append(src, padtext...)
}

// pkcs5Unpad entfernt die Auffuellung - und prueft sie, statt ihr zu glauben.
//
// Die alte Fassung las das letzte Byte als Laenge und schnitt danach ab. Bei
// einem Wert, der sich nicht entschluesseln laesst, ist dieses Byte beliebig:
// bei 32 Byte Laenge und einem letzten Byte von 210 wurde daraus src[:-178] -
// eine Panik. Und weil das in einer Goroutine geschah, riss sie den GANZEN
// Dienst mit, nicht nur diesen einen Charakter. Ein einziger unbrauchbarer
// Datensatz legte damit den Token-Abgleich fuer alle zwoelf lahm, in einer
// Neustartschleife.
//
// Ein falscher Schluessel oder ein beschaedigter Wert ist ein Fehler, kein
// Programmierfehler - also wird er zurueckgegeben und nicht geworfen.
func pkcs5Unpad(src []byte) ([]byte, error) {
	length := len(src)
	if length == 0 {
		return nil, fmt.Errorf("nichts zu entpacken")
	}

	unpadding := int(src[length-1])
	if unpadding == 0 || unpadding > aes.BlockSize || unpadding > length {
		return nil, fmt.Errorf(
			"ungueltige Auffuellung (%d bei %d Bytes) - falscher Schluessel oder beschaedigter Wert",
			unpadding, length)
	}

	// Jedes Fuellbyte muss denselben Wert tragen. Ohne diese Pruefung geht
	// jeder zufaellig passende Wert als gueltig durch, und der Aufrufer
	// arbeitet mit Muell weiter.
	for i := length - unpadding; i < length; i++ {
		if int(src[i]) != unpadding {
			return nil, fmt.Errorf("Auffuellung nicht einheitlich - falscher Schluessel")
		}
	}
	return src[:length-unpadding], nil
}
