#!/bin/sh
# Run on the NAS. Keep the CA private key there; distribute ca.crt only.
set -eu
out=${1:?certificate directory required}
ip=${2:?NAS IPv4 address required}
case "$ip" in *[!0-9.]*|'') echo 'Use a literal NAS IPv4 address' >&2; exit 1;; esac
umask 077
mkdir -p "$out"
chmod 700 "$out"
if [ ! -f "$out/ca.crt" ]; then
 test ! -e "$out/ca.key" || { echo 'Incomplete existing CA; inspect it before retry' >&2; exit 1; }
 openssl ecparam -name prime256v1 -genkey -noout -out "$out/ca.key"
 openssl req -x509 -new -sha256 -key "$out/ca.key" -days 3650 -subj '/CN=Guizhang NAS Private CA' -addext 'basicConstraints=critical,CA:TRUE,pathlen:0' -addext 'keyUsage=critical,keyCertSign,cRLSign' -out "$out/ca.crt"
fi
# Refuse a replaced/mismatched CA rather than mint an unrelated server certificate.
openssl verify -CAfile "$out/ca.crt" "$out/ca.crt" >/dev/null
openssl ecparam -name prime256v1 -genkey -noout -out "$out/server.key.new"
openssl req -new -key "$out/server.key.new" -subj '/CN=Guizhang NAS' -out "$out/server.csr"
cat > "$out/extensions.cnf" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature
extendedKeyUsage=serverAuth
subjectAltName=IP:$ip,IP:127.0.0.1,DNS:localhost
EOF
openssl x509 -req -sha256 -in "$out/server.csr" -CA "$out/ca.crt" -CAkey "$out/ca.key" -set_serial "0x$(openssl rand -hex 16)" -days 365 -extfile "$out/extensions.cnf" -out "$out/server.crt.new"
openssl verify -CAfile "$out/ca.crt" -verify_ip "$ip" "$out/server.crt.new"
mv "$out/server.key.new" "$out/server.key"
mv "$out/server.crt.new" "$out/server.crt"
chmod 600 "$out"/*
openssl x509 -in "$out/ca.crt" -noout -fingerprint -sha256
