package main

import (
	"bytes"
	"crypto/aes"
	"testing"
)

func TestPkcs5UnpadLaesstSichNichtZumAbsturzBringen(t *testing.T) {
	faelle := []struct {
		name string
		src  []byte
	}{
		{"Laenge groesser als der Puffer", append(bytes.Repeat([]byte{7}, 31), 210)},
		{"Laenge genau eins zu gross", append(bytes.Repeat([]byte{7}, 15), 17)},
		{"Null als Laenge", append(bytes.Repeat([]byte{7}, 15), 0)},
		{"leerer Puffer", []byte{}},
		{"Auffuellung nicht einheitlich", append(bytes.Repeat([]byte{1}, 14), 4, 4)},
	}

	for _, f := range faelle {
		t.Run(f.name, func(t *testing.T) {
			// Kein t.Fatal bei Panik noetig: eine Panik hier beendet den Test
			// mit genau der Meldung, um die es geht.
			if _, err := pkcs5Unpad(f.src); err == nil {
				t.Fatalf("erwartet wurde ein Fehler, kein stillschweigendes Ergebnis")
			}
		})
	}
}

func TestPkcs5UnpadEntferntGueltigeAuffuellung(t *testing.T) {
	nutzdaten := []byte("refresh-token-xyz")
	gepolstert := pkcs5Pad(nutzdaten, aes.BlockSize)

	entpackt, err := pkcs5Unpad(gepolstert)
	if err != nil {
		t.Fatalf("gueltige Auffuellung wurde abgelehnt: %v", err)
	}
	if !bytes.Equal(entpackt, nutzdaten) {
		t.Fatalf("erwartet %q, bekommen %q", nutzdaten, entpackt)
	}
}

func TestDecryptAESLehntKrummeLaengeAb(t *testing.T) {
	key := bytes.Repeat([]byte{9}, 32)

	// 16 Byte IV plus 5 Byte "Ciphertext" - kein ganzer Block.
	krumm := "AAAAAAAAAAAAAAAAAAAAAAAAAAAA"

	if _, err := decryptAES(krumm, key); err == nil {
		t.Fatal("erwartet wurde ein Fehler statt einer Panik")
	}
}
