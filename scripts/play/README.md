# Play App Signing encryption key

`encryption_public_key.pem`, when present here, is Google's public key for
encrypting an exported signing key, downloaded from Play Console → Play App
Signing → "Export and upload a key from Java keystore". It is a public key and
the same for every developer; the `Play App Signing key export` workflow uses
it with `--rsa-aes-encryption`, and falls back to Google's published hex key
when the file is absent.
