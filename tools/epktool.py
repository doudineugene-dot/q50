#!/usr/bin/env python3
"""
Разбор и сборка контейнеров .epk головного устройства Infiniti Q50 (InTouch).

ГУ (AppManager, com.connexis.appsmanager) устанавливает нативные приложения с
USB только упакованными в .epk. Формат воспроизведён по декомпилированному
framework.jar (com.connexis.ivi.utils.epk.v2) и сверен с открытой реализацией
qazwsd147/appgarage-dash. Раскладка дополнительно подтверждена разбором двух
реальных образцов (gtr.epk, appgarage-dash-v1.0.epk).

Схема (подтверждена):

    заголовок:
      0x000  4    ".epk"
      0x004  2    version      u16 BE = 2
      0x006  2    payloadType  u16 BE (loader его не проверяет)
      0x008  2    blockCount   u16 BE — число вложенных файлов
      0x00A  2    keySize      u16 BE = 128 (RSA-1024)
      0x00C  256  ключ: RSA(dataKey) слева, нули справа
    затем blockCount раз:
      128  имя файла, ASCIIZ
      4    длина шифроблока, int32 BE
      ...  шифротекст

    dataKey  — 20 случайных байт, добитые нулями до 32 -> ключ AES-256
    шифр     — AES-256-CBC, фиксированный IV 01 23 45 67 89 ab cd ef 00*8
    паддинг  — PKCS5 (добавляет 1..16 байт всегда)
    обёртка  — RSA/ECB/PKCS1v15 открытым ключом OBU (dataKey -> 128 байт)

Подписи/MAC в формате НЕТ — только конфиденциальность. Значит для сборки
загружаемого пакета достаточно ПУБЛИЧНОГО сертификата OBU; закрытый ключ нужен
только чтобы распаковать чужой .epk.

    epktool.py info    файл.epk
    epktool.py build   app.apk -o app.epk --cert keys/obu_cert.pem
    epktool.py build   a.apk b.apk -o bundle.epk --cert keys/obu_cert.pem
    epktool.py parse   app.epk -o out/ --key obu_key.pem      # нужен закрытый ключ
    epktool.py compare a.epk b.epk
"""

import argparse
import os
import struct
import sys

MAGIC = b'.epk'
VERSION = 2
IV = bytes([0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef]) + b'\x00' * 8
ENVELOPE = 0x10C          # заголовок до поля имени
OFF_KEY = 0x00C
KEY_FIELD = 256
NAME_FIELD = 128


# ---- крипто (зеркало AESUtil / RSAUtil) -----------------------------------

def _pkcs5_pad(b):
    n = 16 - (len(b) % 16)
    return b + bytes([n]) * n


def _pkcs5_unpad(b):
    return b[:-b[-1]]


def _aes_key(dk):
    return dk + b'\x00' * (32 - len(dk))     # 20 байт dataKey -> AES-256


def aes_encrypt(data, dk):
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    c = Cipher(algorithms.AES(_aes_key(dk)), modes.CBC(IV)).encryptor()
    return c.update(_pkcs5_pad(data)) + c.finalize()


def aes_decrypt(ct, dk):
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    d = Cipher(algorithms.AES(_aes_key(dk)), modes.CBC(IV)).decryptor()
    return _pkcs5_unpad(d.update(ct) + d.finalize())


def load_public(path):
    """Открытый ключ RSA из X.509-сертификата или голого ключа, PEM или DER."""
    from cryptography.hazmat.primitives import serialization
    from cryptography import x509
    raw = open(path, 'rb').read()
    for load in (lambda d: x509.load_pem_x509_certificate(d).public_key(),
                 lambda d: x509.load_der_x509_certificate(d).public_key(),
                 serialization.load_pem_public_key,
                 serialization.load_der_public_key):
        try:
            return load(raw)
        except Exception:
            continue
    sys.exit('не удалось прочитать открытый ключ/сертификат из %s' % path)


def load_private(path):
    from cryptography.hazmat.primitives.serialization import load_pem_private_key
    return load_pem_private_key(open(path, 'rb').read(), password=None)


# ---- разбор заголовка ------------------------------------------------------

def parse_envelope(data):
    if data[:4] != MAGIC:
        raise ValueError('не .epk: магия %r' % data[:4])
    version, payload_type, blocks, key_size = struct.unpack_from('>HHHH', data, 4)
    return {
        'version': version, 'payload_type': payload_type,
        'block_count': blocks, 'key_size': key_size,
        'key': data[OFF_KEY:OFF_KEY + key_size],
        'key_padding_clean': not any(data[OFF_KEY + key_size:OFF_KEY + KEY_FIELD]),
    }


def iter_blocks(data):
    """Пройти записи после заголовка: (имя, длина, шифротекст, смещение)."""
    off = ENVELOPE
    n = len(data)
    while off + NAME_FIELD + 4 <= n:
        name = data[off:off + NAME_FIELD].rstrip(b'\x00').decode('ascii', 'replace')
        off += NAME_FIELD
        (blen,) = struct.unpack_from('>i', data, off)
        off += 4
        ct = data[off:off + blen]
        yield name, blen, ct, off
        off += blen


# ---- команды ---------------------------------------------------------------

def cmd_info(args):
    data = open(args.file, 'rb').read()
    h = parse_envelope(data)
    print('файл:           %s (%d байт)' % (args.file, len(data)))
    print()
    print('== ЗАГОЛОВОК ==')
    print('  version       = %d' % h['version'])
    print('  payloadType   = %d' % h['payload_type'])
    print('  blockCount    = %d' % h['block_count'])
    print('  keySize       = %d байт (%d бит)%s'
          % (h['key_size'], h['key_size'] * 8,
             '  — RSA-1024' if h['key_size'] == 128 else ''))
    print('  ключ RSA      = %s…' % h['key'][:16].hex())
    print('  добивка ключа = %s' % ('нули' if h['key_padding_clean'] else 'НЕ нули (?)'))
    print()
    print('== БЛОКИ ==')
    total = 0
    import collections, math
    for i, (name, blen, ct, off) in enumerate(iter_blocks(data)):
        total += 1
        e = 0.0
        if ct:
            c = collections.Counter(ct)
            e = -sum((v / len(ct)) * math.log2(v / len(ct)) for v in c.values())
        print('  #%d %-24s %d байт, с 0x%X, энтропия %.2f%s'
              % (i, name, blen, off, e, '' if blen % 16 == 0 else '  (не кратно 16!)'))
        if total >= h['block_count']:
            break
    tail = ENVELOPE + sum(NAME_FIELD + 4 + b for _, b, _, _ in
                          list(iter_blocks(data))[:h['block_count']])
    print()
    print('  сверка размера: %s' % ('сходится' if tail == len(data)
                                    else 'РАСХОЖДЕНИЕ (посчитано %d, файл %d)' % (tail, len(data))))


def cmd_build(args):
    import uuid
    pub = load_public(args.cert)
    dk = uuid.uuid4().hex[:20].encode('ascii')       # 20-байтный dataKey
    from cryptography.hazmat.primitives.asymmetric import padding
    enc_key = pub.encrypt(dk, padding.PKCS1v15())
    if len(enc_key) != 128:
        print('предупреждение: ключ RSA даёт %d байт, ГУ ждёт 128 (RSA-1024)'
              % len(enc_key))

    with open(args.output, 'wb') as f:
        f.write(MAGIC)
        f.write(struct.pack('>HHHH', VERSION, args.type, len(args.inputs), len(enc_key)))
        f.write(enc_key + b'\x00' * (KEY_FIELD - len(enc_key)))
        for p in args.inputs:
            name = os.path.basename(p).encode('ascii')
            if len(name) > NAME_FIELD:
                sys.exit('имя длиннее %d байт: %r' % (NAME_FIELD, name))
            ct = aes_encrypt(open(p, 'rb').read(), dk)
            f.write(name + b'\x00' * (NAME_FIELD - len(name)))
            f.write(struct.pack('>i', len(ct)))
            f.write(ct)

    print('собрано %s (%d байт), вложено файлов: %d, payloadType=%d'
          % (args.output, os.path.getsize(args.output), len(args.inputs), args.type))
    print('скопировать в корень USB (FAT32) и выбрать в AppManager на ГУ.')


def cmd_parse(args):
    from cryptography.hazmat.primitives.asymmetric import padding
    priv = load_private(args.key)
    data = open(args.file, 'rb').read()
    h = parse_envelope(data)
    dk = priv.decrypt(h['key'], padding.PKCS1v15())
    os.makedirs(args.output, exist_ok=True)
    for name, blen, ct, off in list(iter_blocks(data))[:h['block_count']]:
        plain = aes_decrypt(ct, dk)
        dst = os.path.join(args.output, name)
        open(dst, 'wb').write(plain)
        tag = ' (ZIP/APK)' if plain[:4] == b'PK\x03\x04' else ''
        print('  извлечено %s: %d байт%s' % (dst, len(plain), tag))


def cmd_compare(args):
    import collections, math

    def ent(x):
        c = collections.Counter(x)
        return -sum((n / len(x)) * math.log2(n / len(x)) for n in c.values())

    a, b = open(args.file_a, 'rb').read(), open(args.file_b, 'rb').read()
    ha, hb = parse_envelope(a), parse_envelope(b)
    print('== ЗАГОЛОВКИ ==')
    for f in ('version', 'payload_type', 'block_count', 'key_size'):
        print('  %-13s %-6s %-6s %s' % (f, ha[f], hb[f],
                                        'одинаково' if ha[f] == hb[f] else 'РАЗЛИЧАЮТСЯ'))
    same = sum(x == y for x, y in zip(ha['key'], hb['key']))
    print('  ключ RSA: общих байт %d из %d' % (same, len(ha['key'])))

    pa = list(iter_blocks(a))[0][2]
    pb = list(iter_blocks(b))[0][2]
    n = min(len(pa), len(pb))
    x = bytes(p ^ q for p, q in zip(pa[:n], pb[:n]))
    print()
    print('== ПОВТОР ГАММЫ (XOR первых блоков) ==')
    print('  энтропия XOR: %.4f из 8.0' % ent(x))
    print('  вывод: %s' % ('гамма ПОВТОРЯЕТСЯ' if ent(x) < 7.5
                           else 'у каждого пакета свой dataKey (гамма не повторяется)'))


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='cmd', required=True)

    s = sub.add_parser('info', help='разобрать заголовок и блоки')
    s.add_argument('file')
    s.set_defaults(func=cmd_info)

    s = sub.add_parser('build', help='упаковать файл(ы) в .epk')
    s.add_argument('inputs', nargs='+', help='входные файлы')
    s.add_argument('-o', '--output', required=True)
    s.add_argument('--cert', required=True, help='публичный сертификат OBU (X.509)')
    s.add_argument('--type', type=int, default=2)
    s.set_defaults(func=cmd_build)

    s = sub.add_parser('parse', help='распаковать .epk (нужен закрытый ключ)')
    s.add_argument('file')
    s.add_argument('-o', '--output', required=True)
    s.add_argument('--key', required=True, help='закрытый ключ OBU (PEM)')
    s.set_defaults(func=cmd_parse)

    s = sub.add_parser('compare', help='сравнить два .epk')
    s.add_argument('file_a')
    s.add_argument('file_b')
    s.set_defaults(func=cmd_compare)

    args = p.parse_args()
    args.func(args)


if __name__ == '__main__':
    main()
