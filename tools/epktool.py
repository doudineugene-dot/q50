#!/usr/bin/env python3
"""
Разбор и сборка контейнеров .epk (магия ".epk", версия 2).

ВАЖНО: формат восстановлен наблюдением за одним файлом, а не по спецификации.
Смещения могут отличаться в других версиях — все они переопределяются ключами
командной строки.

Содержимое контейнера зашифровано. Ключа в самом файле нет, без него извлечь
вложенный APK невозможно — это не обход защиты, а арифметика: шифр на то и шифр.
Инструмент готов к моменту, когда ключ найдётся (в прошивке ГУ или в утилите
упаковки).

Примеры:
    epktool.py info gtr.epk
    epktool.py extract gtr.epk -o payload.bin
    epktool.py extract gtr.epk -o gtr.apk --key <hex> --mode cbc --iv <hex>
    epktool.py pack q50info.apk -o q50info.epk --name q50info.apk
"""

import argparse
import struct
import sys

MAGIC = b'.epk'

# Смещения, выведенные из разбора gtr.epk (2 156 912 байт).
OFF_SIG = 0x00C        # подпись, длину берём из заголовка
OFF_SLOT2 = 0x08C      # второе поле того же размера, в образце всё нулевое
OFF_NAME = 0x10C       # имя вложенного файла, ASCIIZ
DEFAULT_PAYLOAD = 0x180


def parse_header(data):
    if data[:4] != MAGIC:
        raise ValueError('не контейнер .epk: магия %r вместо %r' % (data[:4], MAGIC))
    v1, v2, v3, siglen = struct.unpack_from('>HHHH', data, 4)
    name_end = data.index(b'\x00', OFF_NAME)
    return {
        'version': (v1, v2, v3),
        'siglen': siglen,
        'signature': data[OFF_SIG:OFF_SIG + siglen],
        'slot2': data[OFF_SLOT2:OFF_SLOT2 + siglen],
        'name': data[OFF_NAME:name_end].decode('ascii', 'replace'),
    }


def cmd_info(args):
    data = open(args.file, 'rb').read()
    h = parse_header(data)
    print('файл:            %s (%d байт)' % (args.file, len(data)))
    print('версия:          %d.%d.%d' % h['version'])
    print('длина подписи:   %d байт (%d бит — похоже на RSA-%d)'
          % (h['siglen'], h['siglen'] * 8, h['siglen'] * 8))
    print('вложенное имя:   %s' % h['name'])
    print('подпись:         %s…' % h['signature'][:16].hex())
    print('второе поле:     %s' % ('всё нули (не используется)'
                                   if not any(h['slot2']) else h['slot2'][:16].hex() + '…'))

    body = data[args.payload:]
    print('нагрузка:        с 0x%03x, %d байт, кратна 16: %s'
          % (args.payload, len(body), len(body) % 16 == 0))

    zip_sigs = [s for s in (b'PK\x03\x04', b'PK\x01\x02', b'PK\x05\x06') if s in data]
    print('признаки ZIP:    %s' % (', '.join(s.hex() for s in zip_sigs) if zip_sigs
                                   else 'нет — содержимое НЕ является открытым APK'))

    import collections, math
    c = collections.Counter(body)
    ent = -sum((n / len(body)) * math.log2(n / len(body)) for n in c.values())
    print('энтропия:        %.3f из 8.0 %s' % (ent, '(зашифровано)' if ent > 7.9 else ''))

    blocks = [body[i:i + 16] for i in range(0, len(body) - 15, 16)]
    dups = len(blocks) - len(set(blocks))
    print('повторы блоков:  %d %s' % (dups, '(режим ECB)' if dups > 10 else '(CBC/CTR или поточный шифр)'))


def crypt(body, args, encrypt):
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    key = bytes.fromhex(args.key)
    iv = bytes.fromhex(args.iv) if args.iv else b'\x00' * 16
    mode = {'cbc': modes.CBC, 'ecb': None, 'ctr': modes.CTR,
            'cfb': modes.CFB, 'ofb': modes.OFB}[args.mode]
    m = modes.ECB() if args.mode == 'ecb' else mode(iv)
    c = Cipher(algorithms.AES(key), m).encryptor() if encrypt \
        else Cipher(algorithms.AES(key), m).decryptor()
    return c.update(body) + c.finalize()


def cmd_extract(args):
    data = open(args.file, 'rb').read()
    h = parse_header(data)
    body = data[args.payload:]
    if args.key:
        if args.mode in ('cbc', 'ecb') and len(body) % 16:
            sys.exit('длина нагрузки %d не кратна 16 — для режима %s нужно '
                     'подобрать --payload' % (len(body), args.mode))
        body = crypt(body, args, encrypt=False)
    out = args.output or h['name']
    open(out, 'wb').write(body)
    print('записано %d байт в %s' % (len(body), out))
    if body[:4] == b'PK\x03\x04':
        print('начинается с PK\\x03\\x04 — это ZIP/APK, расшифровка удалась')
    elif args.key:
        print('на ZIP не похоже: ключ, режим или IV не те')


def cmd_pack(args):
    payload = open(args.file, 'rb').read()
    if args.key:
        if args.mode in ('cbc', 'ecb') and len(payload) % 16:
            payload += b'\x00' * (16 - len(payload) % 16)
        payload = crypt(payload, args, encrypt=True)

    name = (args.name or args.file.split('/')[-1]).encode('ascii')
    siglen = args.siglen
    head = bytearray(args.payload)
    head[0:4] = MAGIC
    struct.pack_into('>HHHH', head, 4, *args.version, siglen)
    # Поле подписи оставляем нулевым: закрытого ключа нет. Если ГУ подпись
    # проверяет, такой пакет оно отвергнет.
    head[OFF_NAME:OFF_NAME + len(name)] = name

    open(args.output, 'wb').write(bytes(head) + payload)
    print('собрано %s: заголовок %d + нагрузка %d = %d байт'
          % (args.output, len(head), len(payload), len(head) + len(payload)))
    print('ВНИМАНИЕ: поле подписи нулевое. Если устройство проверяет подпись, '
          'пакет будет отвергнут.')


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='cmd', required=True)

    def crypto_args(sp):
        sp.add_argument('--key', help='ключ AES в hex (32 симв. = AES-128, 64 = AES-256)')
        sp.add_argument('--iv', help='IV в hex, по умолчанию нули')
        sp.add_argument('--mode', default='cbc', choices=['cbc', 'ecb', 'ctr', 'cfb', 'ofb'])

    sp = sub.add_parser('info', help='разобрать заголовок')
    sp.add_argument('file')
    sp.add_argument('--payload', type=lambda x: int(x, 0), default=DEFAULT_PAYLOAD)
    sp.set_defaults(func=cmd_info)

    sp = sub.add_parser('extract', help='извлечь нагрузку')
    sp.add_argument('file')
    sp.add_argument('-o', '--output')
    sp.add_argument('--payload', type=lambda x: int(x, 0), default=DEFAULT_PAYLOAD)
    crypto_args(sp)
    sp.set_defaults(func=cmd_extract)

    sp = sub.add_parser('pack', help='упаковать файл в контейнер .epk')
    sp.add_argument('file')
    sp.add_argument('-o', '--output', required=True)
    sp.add_argument('--name', help='имя вложенного файла в заголовке')
    sp.add_argument('--payload', type=lambda x: int(x, 0), default=DEFAULT_PAYLOAD)
    sp.add_argument('--siglen', type=int, default=128)
    sp.add_argument('--version', type=int, nargs=3, default=[2, 2, 1])
    crypto_args(sp)
    sp.set_defaults(func=cmd_pack)

    args = p.parse_args()
    args.func(args)


if __name__ == '__main__':
    main()
