#!/usr/bin/env python3
"""
Поиск X.509-сертификатов и открытых ключей RSA в произвольных данных.

Публичный сертификат OBU, нужный для сборки .epk, нигде не прячется — он
публичный по определению. Значит он лежит в открытом виде: в упаковщике
(AppGarage), в прошивке ГУ или в файловой системе устройства. Этот сканер
проходит по любому файлу или каталогу и вытаскивает всё, что похоже на
сертификат или открытый ключ, включая ключи, зашитые в бинарники.

Находит:
  - PEM-блоки (CERTIFICATE, PUBLIC KEY, RSA PUBLIC KEY) в тексте и внутри бинарей
  - DER X.509-сертификаты
  - DER SubjectPublicKeyInfo (голый открытый ключ RSA)

Каждую находку сохраняет отдельным .pem и печатает её параметры, чтобы
сразу отсеять мусор: модуль на 1024 бита — кандидат в ключ OBU (m_keySize в
EPK = 128 байт = 1024 бита).

    certscan.py firmware.bin
    certscan.py AppGarage.exe -o found/
    certscan.py /path/to/rootfs -o found/ --min-bits 512
"""

import argparse
import os
import re
import sys

PEM_RE = re.compile(
    rb'-----BEGIN ([A-Z0-9 ]+?)-----.*?-----END \1-----',
    re.DOTALL)


def load_cert(der):
    from cryptography import x509
    return x509.load_der_x509_certificate(der)


def load_spki(der):
    from cryptography.hazmat.primitives.serialization import load_der_public_key
    return load_der_public_key(der)


def der_lengths(data, start):
    """Разобрать длину DER-объекта, начинающегося с 0x30 0x82 (SEQUENCE,
    длинная форма 2 байта) — этого хватает для сертификатов и ключей
    практического размера."""
    if data[start] != 0x30:
        return None
    b = data[start + 1]
    if b == 0x82:
        n = (data[start + 2] << 8) | data[start + 3]
        return 4 + n
    if b == 0x81:
        return 3 + data[start + 2]
    if b < 0x80:
        return 2 + b
    return None


def scan_blob(data):
    """Вернуть список находок: (kind, der_bytes, object)."""
    found = []
    seen = set()

    # 1. PEM в любом виде — и в тексте, и вкраплённый в бинарь
    from base64 import b64decode
    for m in PEM_RE.finditer(data):
        label = m.group(1).decode('ascii', 'replace')
        body = re.sub(rb'[^A-Za-z0-9+/=]', b'', m.group(0).split(b'-----')[2])
        try:
            der = b64decode(body)
        except Exception:
            continue
        _classify(der, found, seen, note='PEM ' + label)

    # 2. DER: каждая позиция 0x30 0x8x — потенциальный SEQUENCE
    i = 0
    n = len(data)
    while i < n - 4:
        if data[i] == 0x30 and data[i + 1] in (0x81, 0x82):
            length = der_lengths(data, i)
            if length and 64 <= length <= 8192 and i + length <= n:
                _classify(data[i:i + length], found, seen, note='DER @0x%x' % i)
                # не прыгаем через объект: вложенные ключи внутри сертификата
                # нам тоже интересны как отдельная находка
        i += 1
    return found


def _classify(der, found, seen, note):
    h = hash(der)
    if h in seen:
        return
    try:
        cert = load_cert(der)
        seen.add(h)
        found.append(('cert', der, cert, note))
        return
    except Exception:
        pass
    try:
        key = load_spki(der)
        seen.add(h)
        found.append(('pubkey', der, key, note))
    except Exception:
        pass


def describe(kind, obj):
    from cryptography.hazmat.primitives.asymmetric import rsa
    if kind == 'cert':
        pub = obj.public_key()
        subj = obj.subject.rfc4514_string()
        who = 'subject=%s' % subj
    else:
        pub = obj
        who = 'открытый ключ'
    if isinstance(pub, rsa.RSAPublicKey):
        bits = pub.key_size
        e = pub.public_numbers().e
        return bits, 'RSA-%d e=%d, %s' % (bits, e, who)
    return 0, 'не RSA (%s), %s' % (type(pub).__name__, who)


def to_pem(kind, obj):
    from cryptography.hazmat.primitives import serialization
    if kind == 'cert':
        return obj.public_bytes(serialization.Encoding.PEM)
    return obj.public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo)


def iter_files(path):
    if os.path.isfile(path):
        yield path
    else:
        for root, _, files in os.walk(path):
            for f in files:
                yield os.path.join(root, f)


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('path', help='файл или каталог')
    p.add_argument('-o', '--out', default='certscan-out', help='куда сохранять находки')
    p.add_argument('--min-bits', type=int, default=0,
                   help='показывать только ключи не меньше N бит')
    p.add_argument('--max-size', type=int, default=64 * 1024 * 1024,
                   help='пропускать файлы больше N байт')
    args = p.parse_args()

    total = 0
    os.makedirs(args.out, exist_ok=True)
    for fpath in iter_files(args.path):
        try:
            if os.path.getsize(fpath) > args.max_size:
                continue
            data = open(fpath, 'rb').read()
        except Exception:
            continue
        for kind, der, obj, note in scan_blob(data):
            bits, desc = describe(kind, obj)
            if bits < args.min_bits:
                continue
            flag = '  <-- 1024 бита, кандидат в ключ OBU' if bits == 1024 else ''
            total += 1
            name = '%s/%s-%03d.pem' % (args.out, kind, total)
            open(name, 'wb').write(to_pem(kind, obj))
            src = fpath if os.path.isdir(args.path) else ''
            print('[%s] %s %s%s' % (kind, desc, note, flag))
            print('       из %s -> %s' % (src or fpath, name))

    print('\nвсего находок: %d (в %s/)' % (total, args.out))
    if total == 0:
        print('ничего не найдено — либо в данных нет сертификатов, либо они '
              'сжаты/зашифрованы (распакуй прошивку и повтори)')


if __name__ == '__main__':
    main()
