#!/usr/bin/env python3
"""
Разбор и сборка контейнеров .epk головного устройства Infiniti Q50.

Раскладка подтверждена двумя независимыми источниками:

1. Имена и порядок полей конверта — из декомпилированного парсера IVI
   com.connexis.ivi.utils.epk.v2, класс EpkParseResult.EpkEntry.Envelope
   (проект oneezeeroo/Q50-Reverse-Engineering).
2. Точные смещения и длины — разбором образца gtr.epk: заявленная длина
   нагрузки сходится с размером файла до байта.

    0x000   4     m_magicCode    ".epk"
    0x004   2     m_version      u16 BE
    0x006   2     m_payloadType  u16 BE
    0x008   2     m_blockCount   u16 BE — число записей
    0x00A   2     m_keySize      u16 BE — значащих байт в m_key
    0x00C   256   m_key[256]     буфер фиксированной длины
    ------------- конец конверта, 268 байт
    0x10C   128   имя вложенного файла, ASCIIZ
    0x18C   4     длина нагрузки, u32 BE
    0x190   ...   нагрузка

Нагрузка зашифрована. m_keySize = 128 байт = 1024 бита — это размер
шифротекста RSA-1024, то есть симметричный ключ завёрнут в RSA и лежит прямо
в файле. Чтобы его развернуть, нужен ЗАКРЫТЫЙ ключ RSA из прошивки ГУ.

Примеры:
    epktool.py info gtr.epk
    epktool.py extract gtr.epk -o payload.bin          # сырая нагрузка
    epktool.py extract gtr.epk -o gtr.apk --rsa-key ivi-private.pem
    epktool.py extract gtr.epk -o gtr.apk --key <hex> --mode cbc
    epktool.py pack q50info.apk -o q50info.epk --name q50info.apk
"""

import argparse
import struct
import sys

MAGIC = b'.epk'
ENVELOPE_SIZE = 0x10C
OFF_KEY = 0x00C
KEY_FIELD = 256
NAME_FIELD = 128


def parse(data):
    if data[:4] != MAGIC:
        raise ValueError('не контейнер .epk: магия %r вместо %r' % (data[:4], MAGIC))
    version, payload_type, blocks, key_size = struct.unpack_from('>HHHH', data, 4)
    name_off = ENVELOPE_SIZE
    len_off = name_off + NAME_FIELD
    data_off = len_off + 4
    return {
        'version': version,
        'payload_type': payload_type,
        'block_count': blocks,
        'key_size': key_size,
        'key': data[OFF_KEY:OFF_KEY + key_size],
        'key_padding_clean': not any(data[OFF_KEY + key_size:OFF_KEY + KEY_FIELD]),
        'name': data[name_off:data.index(b'\x00', name_off)].decode('ascii', 'replace'),
        'declared_len': struct.unpack_from('>I', data, len_off)[0],
        'data_off': data_off,
    }


def cmd_info(args):
    data = open(args.file, 'rb').read()
    h = parse(data)
    print('файл:           %s (%d байт)' % (args.file, len(data)))
    print()
    print('== КОНВЕРТ ==')
    print('  m_version     = %d' % h['version'])
    print('  m_payloadType = %d' % h['payload_type'])
    print('  m_blockCount  = %d' % h['block_count'])
    print('  m_keySize     = %d байт (%d бит)%s'
          % (h['key_size'], h['key_size'] * 8,
             '  — размер шифротекста RSA-1024' if h['key_size'] == 128 else ''))
    print('  m_key         = %s…' % h['key'][:16].hex())
    print('  добивка ключа = %s' % ('нули, как и ожидалось' if h['key_padding_clean']
                                    else 'НЕ нули — раскладка может отличаться'))
    print()
    print('== ЗАПИСЬ ==')
    print('  имя файла     = %s' % h['name'])
    print('  длина         = %d байт, данные с 0x%X' % (h['declared_len'], h['data_off']))

    actual = len(data) - h['data_off']
    ok = actual == h['declared_len']
    print('  сверка длины  = %s (в файле %d)'
          % ('СОВПАДАЕТ' if ok else 'РАСХОЖДЕНИЕ', actual))
    print('  кратна 16     = %s' % (h['declared_len'] % 16 == 0))

    body = data[h['data_off']:h['data_off'] + h['declared_len']]
    print()
    print('== НАГРУЗКА ==')
    zips = [s for s in (b'PK\x03\x04', b'PK\x01\x02', b'PK\x05\x06') if s in body]
    print('  признаки ZIP  = %s' % (', '.join(s.hex() for s in zips) if zips
                                    else 'нет — зашифровано'))
    import collections, math
    c = collections.Counter(body)
    ent = -sum((n / len(body)) * math.log2(n / len(body)) for n in c.values())
    print('  энтропия      = %.3f из 8.0' % ent)
    blocks = [body[i:i + 16] for i in range(0, len(body) - 15, 16)]
    dups = len(blocks) - len(set(blocks))
    print('  повторы бл.   = %d %s' % (dups, '(ECB)' if dups > 10 else '(не ECB)'))


def _strip_pkcs1(block):
    """Снять добивку PKCS#1 v1.5: 00 BT <добивка> 00 <данные>, BT = 01 или 02."""
    if block[0] != 0 or block[1] not in (1, 2):
        raise ValueError('не похоже на PKCS#1 v1.5: первые байты %s' % block[:2].hex())
    sep = block.index(b'\x00', 2)
    return block[sep + 1:]


def unwrap_private(wrapped, pem_path, padding_name):
    """Разворот закрытым ключом — обычная гибридная схема.

    Подходит, если ГУ хранит ЗАКРЫТЫЙ ключ и сам расшифровывает m_key.
    """
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding as pad
    priv = serialization.load_pem_private_key(open(pem_path, 'rb').read(), password=None)
    scheme = (pad.PKCS1v15() if padding_name == 'pkcs1v15'
              else pad.OAEP(mgf=pad.MGF1(hashes.SHA1()), algorithm=hashes.SHA1(), label=None))
    key = priv.decrypt(wrapped, scheme)
    print('ключ развёрнут закрытым ключом: %d байт (AES-%d)' % (len(key), len(key) * 8))
    return key


def load_public(path):
    """Открытый ключ RSA из чего угодно: голый ключ или X.509-сертификат,
    PEM или DER. Публичный сертификат OBU — это X.509, ключ внутри него."""
    from cryptography.hazmat.primitives import serialization
    from cryptography import x509
    raw = open(path, 'rb').read()
    loaders = [
        serialization.load_pem_public_key,
        serialization.load_der_public_key,
        lambda d: x509.load_pem_x509_certificate(d).public_key(),
        lambda d: x509.load_der_x509_certificate(d).public_key(),
    ]
    errors = []
    for load in loaders:
        try:
            return load(raw)
        except Exception as e:
            errors.append(str(e).splitlines()[0])
    sys.exit('не удалось прочитать открытый ключ из %s:\n  %s'
             % (path, '\n  '.join(errors)))


def unwrap_public(wrapped, pem_path):
    """Разворот ОТКРЫТЫМ ключом — сырая операция RSA.

    Встречается во встраиваемых системах: упаковщик «подписывает» сеансовый
    ключ закрытым ключом, а устройство разворачивает его открытым. Тогда
    открытый ключ лежит в прошивке ГУ, и этого достаточно, чтобы расшифровать
    чужой пакет. Библиотека такую операцию не предоставляет, считаем вручную.
    """
    pub = load_public(pem_path)
    n = pub.public_numbers().n
    e = pub.public_numbers().e
    size = (n.bit_length() + 7) // 8
    m = pow(int.from_bytes(wrapped, 'big'), e, n)
    key = _strip_pkcs1(m.to_bytes(size, 'big'))
    print('ключ развёрнут открытым ключом: %d байт (AES-%d)' % (len(key), len(key) * 8))
    return key


def wrap_public(key, pem_path):
    """Завернуть сеансовый ключ открытым ключом — для сборки своего пакета."""
    from cryptography.hazmat.primitives.asymmetric import padding as pad
    pub = load_public(pem_path)
    return pub.encrypt(key, pad.PKCS1v15())


def aes(body, key, mode_name, iv, encrypt):
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    m = {'ecb': lambda: modes.ECB(), 'cbc': lambda: modes.CBC(iv),
         'ctr': lambda: modes.CTR(iv), 'cfb': lambda: modes.CFB(iv),
         'ofb': lambda: modes.OFB(iv)}[mode_name]()
    c = Cipher(algorithms.AES(key), m)
    op = c.encryptor() if encrypt else c.decryptor()
    return op.update(body) + op.finalize()


def resolve_key(h, args):
    if args.rsa_key:
        return unwrap_private(h['key'], args.rsa_key, args.rsa_padding)
    if args.rsa_pub:
        return unwrap_public(h['key'], args.rsa_pub)
    if args.key:
        return bytes.fromhex(args.key)
    return None


def cmd_extract(args):
    data = open(args.file, 'rb').read()
    h = parse(data)
    body = data[h['data_off']:h['data_off'] + h['declared_len']]

    key = resolve_key(h, args)
    if key:
        iv = bytes.fromhex(args.iv) if args.iv else b'\x00' * 16
        if args.mode in ('cbc', 'ecb') and len(body) % 16:
            sys.exit('длина %d не кратна 16 — режим %s не подойдёт' % (len(body), args.mode))
        body = aes(body, key, args.mode, iv, encrypt=False)

    out = args.output or h['name']
    open(out, 'wb').write(body)
    print('записано %d байт в %s' % (len(body), out))
    if body[:4] == b'PK\x03\x04':
        print('начинается с PK\\x03\\x04 — это ZIP/APK, расшифровка удалась')
    elif key:
        print('на ZIP не похоже: не тот ключ, режим или IV')
    else:
        print('ключ не задан — это сырой шифротекст')


def cmd_pack(args):
    import os
    payload = open(args.file, 'rb').read()

    wrapped = b''
    key = bytes.fromhex(args.key) if args.key else None
    if args.rsa_pub and not key:
        key = os.urandom(args.aes_bytes)   # сеансовый ключ, свой на каждый пакет
    if key:
        if args.mode in ('cbc', 'ecb') and len(payload) % 16:
            payload += b'\x00' * (16 - len(payload) % 16)
        payload = aes(payload, key, args.mode,
                      bytes.fromhex(args.iv) if args.iv else b'\x00' * 16, encrypt=True)
        if args.rsa_pub:
            wrapped = wrap_public(key, args.rsa_pub)
            print('сеансовый ключ AES-%d завёрнут в RSA (%d байт)'
                  % (len(key) * 8, len(wrapped)))

    name = (args.name or args.file.split('/')[-1]).encode('ascii')
    if len(name) >= NAME_FIELD:
        sys.exit('имя длиннее %d байт' % (NAME_FIELD - 1))

    out = bytearray(ENVELOPE_SIZE + NAME_FIELD + 4)
    out[0:4] = MAGIC
    struct.pack_into('>HHHH', out, 4, args.version, args.payload_type, 1,
                     len(wrapped) or args.key_size)
    if wrapped:
        out[OFF_KEY:OFF_KEY + len(wrapped)] = wrapped
    out[ENVELOPE_SIZE:ENVELOPE_SIZE + len(name)] = name
    struct.pack_into('>I', out, ENVELOPE_SIZE + NAME_FIELD, len(payload))

    open(args.output, 'wb').write(bytes(out) + payload)
    print('собрано %s: заголовок %d + нагрузка %d = %d байт'
          % (args.output, len(out), len(payload), len(out) + len(payload)))
    if not wrapped:
        print('ВНИМАНИЕ: поле m_key нулевое — без --rsa-pub устройство пакет отвергнет.')


def cmd_compare(args):
    """Сравнить два контейнера: раскладку и признаки слабостей в шифровании."""
    import collections, math

    def entropy(x):
        c = collections.Counter(x)
        return -sum((n / len(x)) * math.log2(n / len(x)) for n in c.values())

    a, b = open(args.file_a, 'rb').read(), open(args.file_b, 'rb').read()
    ha, hb = parse(a), parse(b)

    print('== КОНВЕРТЫ ==')
    for field in ('version', 'payload_type', 'block_count', 'key_size'):
        va, vb = ha[field], hb[field]
        print('  %-13s %-8s %-8s %s' % (field, va, vb, 'одинаково' if va == vb else 'РАЗЛИЧАЮТСЯ'))
    print('  %-13s %-8s %-8s' % ('имя', ha['name'], hb['name']))
    print('  %-13s %-8d %-8d' % ('длина', ha['declared_len'], hb['declared_len']))

    same = sum(x == y for x, y in zip(ha['key'], hb['key']))
    print()
    print('== m_key ==')
    print('  совпадают целиком: %s' % (ha['key'] == hb['key']))
    print('  общих байт в тех же позициях: %d из %d' % (same, len(ha['key'])))

    pa = a[ha['data_off']:ha['data_off'] + ha['declared_len']]
    pb = b[hb['data_off']:hb['data_off'] + hb['declared_len']]
    n = min(len(pa), len(pb))
    prefix = next((i for i in range(n) if pa[i] != pb[i]), n)

    print()
    print('== ШИФРОТЕКСТЫ ==')
    print('  первый блок A: %s' % pa[:16].hex())
    print('  первый блок B: %s' % pb[:16].hex())
    print('  общий префикс: %d байт' % prefix)

    # Если гамма переиспользована (CTR/OFB с тем же ключом и IV), то
    # C1 xor C2 = P1 xor P2, и энтропия XOR заметно просядет: два ZIP-архива
    # дают куда менее случайный результат, чем два независимых шифротекста.
    x = bytes(p ^ q for p, q in zip(pa[:n], pb[:n]))
    e = entropy(x)
    print()
    print('== ПОВТОР ГАММЫ ==')
    print('  энтропия XOR: %.4f из 8.0' % e)
    print('  нулевых байт: %d из %d (при случайности ~%d)' % (x.count(0), n, n // 256))
    print('  вывод: %s' % ('ГАММА ПОВТОРЯЕТСЯ — ключ и IV переиспользованы, '
                           'нагрузку можно вскрыть без ключа'
                           if e < 7.5 else
                           'гамма не повторяется — у каждого пакета свой сеансовый ключ'))


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='cmd', required=True)

    def crypto(sp):
        sp.add_argument('--key', help='симметричный ключ AES в hex, если он уже известен')
        sp.add_argument('--rsa-key', help='ЗАКРЫТЫЙ ключ RSA (PEM): разворот m_key')
        sp.add_argument('--rsa-pub', help='открытый ключ RSA или X.509-сертификат '
                                          '(PEM/DER) — например публичный сертификат '
                                          'OBU: заворот при сборке, сырой разворот '
                                          'при извлечении')
        sp.add_argument('--rsa-padding', default='pkcs1v15', choices=['pkcs1v15', 'oaep'])
        sp.add_argument('--iv', help='IV в hex, по умолчанию нули')
        sp.add_argument('--mode', default='cbc', choices=['cbc', 'ecb', 'ctr', 'cfb', 'ofb'])

    sp = sub.add_parser('info', help='разобрать заголовок')
    sp.add_argument('file')
    sp.set_defaults(func=cmd_info)

    sp = sub.add_parser('extract', help='извлечь нагрузку')
    sp.add_argument('file')
    sp.add_argument('-o', '--output')
    crypto(sp)
    sp.set_defaults(func=cmd_extract)

    sp = sub.add_parser('pack', help='собрать контейнер')
    sp.add_argument('file')
    sp.add_argument('-o', '--output', required=True)
    sp.add_argument('--name')
    sp.add_argument('--version', type=int, default=2)
    sp.add_argument('--payload-type', type=int, default=2)
    sp.add_argument('--key-size', type=int, default=128)
    sp.add_argument('--aes-bytes', type=int, default=16, choices=[16, 24, 32],
                    help='длина сеансового ключа AES при сборке')
    crypto(sp)
    sp.set_defaults(func=cmd_pack)

    sp = sub.add_parser('compare', help='сравнить два контейнера')
    sp.add_argument('file_a')
    sp.add_argument('file_b')
    sp.set_defaults(func=cmd_compare)

    args = p.parse_args()
    args.func(args)


if __name__ == '__main__':
    main()
