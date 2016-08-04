package org.saiku.datasources.connection.encrypt;

/**
 * DES en/decoder
 *
 */
public final class Des
{

    /*
     * Some useful constants, such as the number of bits in the key.
     */

    private final static int DES_BITS_IN_KEY = 56;

    private final static int DES_ROUNDS = 16; // Iterations of the convolution

    public final static int DES_BLOCK_BYTES = 8;// 64 bits, to be precise
    /*
     * Bits used in the m_Flags field.
     */

    private final static int DF_KEYSET = 0x01;// Key(s) set.

    private final static int DF_3DES = 0x02;// Triple DES in operation.

    private final static int s_IP[] = { 0x00000000, 0x00000001, 0x00000000, 0x00000001, /* 00 - 03 */
            0x00000100, 0x00000101, 0x00000100, 0x00000101, /* 04 - 07 */
            0x00000000, 0x00000001, 0x00000000, 0x00000001, /* 08 - 0b */
            0x00000100, 0x00000101, 0x00000100, 0x00000101, /* 0c - 0f */
            0x00010000, 0x00010001, 0x00010000, 0x00010001, /* 10 - 13 */
            0x00010100, 0x00010101, 0x00010100, 0x00010101, /* 14 - 17 */
            0x00010000, 0x00010001, 0x00010000, 0x00010001, /* 18 - 1b */
            0x00010100, 0x00010101, 0x00010100, 0x00010101, /* 1c - 1f */
            0x00000000, 0x00000001, 0x00000000, 0x00000001, /* 20 - 23 */
            0x00000100, 0x00000101, 0x00000100, 0x00000101, /* 24 - 27 */
            0x00000000, 0x00000001, 0x00000000, 0x00000001, /* 28 - 2b */
            0x00000100, 0x00000101, 0x00000100, 0x00000101, /* 2c - 2f */
            0x00010000, 0x00010001, 0x00010000, 0x00010001, /* 30 - 33 */
            0x00010100, 0x00010101, 0x00010100, 0x00010101, /* 34 - 37 */
            0x00010000, 0x00010001, 0x00010000, 0x00010001, /* 38 - 3b */
            0x00010100, 0x00010101, 0x00010100, 0x00010101, /* 3c - 3f */
            0x01000000, 0x01000001, 0x01000000, 0x01000001, /* 40 - 43 */
            0x01000100, 0x01000101, 0x01000100, 0x01000101, /* 44 - 47 */
            0x01000000, 0x01000001, 0x01000000, 0x01000001, /* 48 - 4b */
            0x01000100, 0x01000101, 0x01000100, 0x01000101, /* 4c - 4f */
            0x01010000, 0x01010001, 0x01010000, 0x01010001, /* 50 - 53 */
            0x01010100, 0x01010101, 0x01010100, 0x01010101, /* 54 - 57 */
            0x01010000, 0x01010001, 0x01010000, 0x01010001, /* 58 - 5b */
            0x01010100, 0x01010101, 0x01010100, 0x01010101, /* 5c - 5f */
            0x01000000, 0x01000001, 0x01000000, 0x01000001, /* 60 - 63 */
            0x01000100, 0x01000101, 0x01000100, 0x01000101, /* 64 - 67 */
            0x01000000, 0x01000001, 0x01000000, 0x01000001, /* 68 - 6b */
            0x01000100, 0x01000101, 0x01000100, 0x01000101, /* 6c - 6f */
            0x01010000, 0x01010001, 0x01010000, 0x01010001, /* 70 - 73 */
            0x01010100, 0x01010101, 0x01010100, 0x01010101, /* 74 - 77 */
            0x01010000, 0x01010001, 0x01010000, 0x01010001, /* 78 - 7b */
            0x01010100, 0x01010101, 0x01010100, 0x01010101, /* 7c - 7f */
    };

    private final static byte s_KP[] = { 4, 12, 20, 28, 3, 11, 19, 27, 35, 43, 51, 59, 2, 10, /* 00 - 0d */
            18, 26, 34, 42, 50, 58, 1, 9, 17, 25, 33, 41, 49, 57,/* 0e - 1b */
            36, 44, 52, 60, 5, 13, 21, 29, 37, 45, 53, 61, 6, 14,/* 1c - 29 */
            22, 30, 38, 46, 54, 62, 7, 15, 23, 31, 39, 47, 55, 63,/* 2a - 37 */
    };

    private final static int DES_NUM_WEAK_KEYS = 64;

    private final static int s_KeyWeak[][] = { { 0x0000000, 0x0000000 },
            { 0xfffffff, 0x0000000 },
            { 0x0000000, 0xfffffff },
            { 0xfffffff, 0xfffffff },
            { 0xaaaaaaa, 0xaaaaaaa },
            { 0x5555555, 0x5555555 },
            { 0x5555555, 0xaaaaaaa },
            { 0xaaaaaaa, 0x5555555 },
            { 0x0000000, 0xaaaaaaa },
            { 0x0000000, 0x5555555 },
            { 0xfffffff, 0xaaaaaaa },
            { 0xfffffff, 0x5555555 },
            { 0xaaaaaaa, 0x0000000 },
            { 0x5555555, 0x0000000 },
            { 0xaaaaaaa, 0xfffffff },
            { 0x5555555, 0xfffffff },
            { 0x3333333, 0x0000000 },
            { 0x6666666, 0x0000000 },
            { 0x9999999, 0x0000000 },
            { 0xccccccc, 0x0000000 },
            { 0x0000000, 0x3333333 },
            { 0x3333333, 0x3333333 },
            { 0x5555555, 0x3333333 },
            { 0x6666666, 0x3333333 },
            { 0x9999999, 0x3333333 },
            { 0xaaaaaaa, 0x3333333 },
            { 0xccccccc, 0x3333333 },
            { 0xfffffff, 0x3333333 },
            { 0x3333333, 0x5555555 },
            { 0x6666666, 0x5555555 },
            { 0x9999999, 0x5555555 },
            { 0xccccccc, 0x5555555 },
            { 0x0000000, 0x6666666 },
            { 0x3333333, 0x6666666 },
            { 0x5555555, 0x6666666 },
            { 0x6666666, 0x6666666 },
            { 0x9999999, 0x6666666 },
            { 0xaaaaaaa, 0x6666666 },
            { 0xccccccc, 0x6666666 },
            { 0xfffffff, 0x6666666 },
            { 0x0000000, 0x9999999 },
            { 0x3333333, 0x9999999 },
            { 0x5555555, 0x9999999 },
            { 0x6666666, 0x9999999 },
            { 0x9999999, 0x9999999 },
            { 0xaaaaaaa, 0x9999999 },
            { 0xccccccc, 0x9999999 },
            { 0xfffffff, 0x9999999 },
            { 0x3333333, 0xaaaaaaa },
            { 0x6666666, 0xaaaaaaa },
            { 0x9999999, 0xaaaaaaa },
            { 0xccccccc, 0xaaaaaaa },
            { 0x0000000, 0xccccccc },
            { 0x3333333, 0xccccccc },
            { 0x5555555, 0xccccccc },
            { 0x6666666, 0xccccccc },
            { 0x9999999, 0xccccccc },
            { 0xaaaaaaa, 0xccccccc },
            { 0xccccccc, 0xccccccc },
            { 0xfffffff, 0xccccccc },
            { 0x3333333, 0xfffffff },
            { 0x6666666, 0xfffffff },
            { 0x9999999, 0xfffffff },
            { 0xccccccc, 0xfffffff },

    };

    private final static short s_KRot = 0x7efc;

    private final static byte s_KPerm[] = { 28, 19, 49, 6, 58, 53, 6, 25, /*
                                                                             * 0 - 7
                                                                             */
            18, 52, 6, 60, 21, 27, 61, 17, /* 8 - 15 */
            51, 59, 24, 6, 48, 20, 26, 56, /* 16 - 23 */
            50, 29, 16, 57, 34, 40, 6, 32, /* 24 - 31 */
            44, 11, 3, 36, 8, 41, 5, 10, /* 32 - 39 */
            37, 6, 4, 45, 12, 35, 6, 42, /* 40 - 47 */
            2, 6, 33, 9, 0, 43, 13, 1, /* 48 - 55 */
    };

    private final static int s_SB[][] = { { 0x01010400,
            0x00000000,
            0x00010000,
            0x01010404,
            0x01010004,
            0x00010404,
            0x00000004,
            0x00010000,
            0x00000400,
            0x01010400,
            0x01010404,
            0x00000400,
            0x01000404,
            0x01010004,
            0x01000000,
            0x00000004,
            0x00000404,
            0x01000400,
            0x01000400,
            0x00010400,
            0x00010400,
            0x01010000,
            0x01010000,
            0x01000404,
            0x00010004,
            0x01000004,
            0x01000004,
            0x00010004,
            0x00000000,
            0x00000404,
            0x00010404,
            0x01000000,
            0x00010000,
            0x01010404,
            0x00000004,
            0x01010000,
            0x01010400,
            0x01000000,
            0x01000000,
            0x00000400,
            0x01010004,
            0x00010000,
            0x00010400,
            0x01000004,
            0x00000400,
            0x00000004,
            0x01000404,
            0x00010404,
            0x01010404,
            0x00010004,
            0x01010000,
            0x01000404,
            0x01000004,
            0x00000404,
            0x00010404,
            0x01010400,
            0x00000404,
            0x01000400,
            0x01000400,
            0x00000000,
            0x00010004,
            0x00010400,
            0x00000000,
            0x01010004, },
            { 0x80108020,
                    0x80008000,
                    0x00008000,
                    0x00108020,
                    0x00100000,
                    0x00000020,
                    0x80100020,
                    0x80008020,
                    0x80000020,
                    0x80108020,
                    0x80108000,
                    0x80000000,
                    0x80008000,
                    0x00100000,
                    0x00000020,
                    0x80100020,
                    0x00108000,
                    0x00100020,
                    0x80008020,
                    0x00000000,
                    0x80000000,
                    0x00008000,
                    0x00108020,
                    0x80100000,
                    0x00100020,
                    0x80000020,
                    0x00000000,
                    0x00108000,
                    0x00008020,
                    0x80108000,
                    0x80100000,
                    0x00008020,
                    0x00000000,
                    0x00108020,
                    0x80100020,
                    0x00100000,
                    0x80008020,
                    0x80100000,
                    0x80108000,
                    0x00008000,
                    0x80100000,
                    0x80008000,
                    0x00000020,
                    0x80108020,
                    0x00108020,
                    0x00000020,
                    0x00008000,
                    0x80000000,
                    0x00008020,
                    0x80108000,
                    0x00100000,
                    0x80000020,
                    0x00100020,
                    0x80008020,
                    0x80000020,
                    0x00100020,
                    0x00108000,
                    0x00000000,
                    0x80008000,
                    0x00008020,
                    0x80000000,
                    0x80100020,
                    0x80108020,
                    0x00108000, },
            { 0x00000208,
                    0x08020200,
                    0x00000000,
                    0x08020008,
                    0x08000200,
                    0x00000000,
                    0x00020208,
                    0x08000200,
                    0x00020008,
                    0x08000008,
                    0x08000008,
                    0x00020000,
                    0x08020208,
                    0x00020008,
                    0x08020000,
                    0x00000208,
                    0x08000000,
                    0x00000008,
                    0x08020200,
                    0x00000200,
                    0x00020200,
                    0x08020000,
                    0x08020008,
                    0x00020208,
                    0x08000208,
                    0x00020200,
                    0x00020000,
                    0x08000208,
                    0x00000008,
                    0x08020208,
                    0x00000200,
                    0x08000000,
                    0x08020200,
                    0x08000000,
                    0x00020008,
                    0x00000208,
                    0x00020000,
                    0x08020200,
                    0x08000200,
                    0x00000000,
                    0x00000200,
                    0x00020008,
                    0x08020208,
                    0x08000200,
                    0x08000008,
                    0x00000200,
                    0x00000000,
                    0x08020008,
                    0x08000208,
                    0x00020000,
                    0x08000000,
                    0x08020208,
                    0x00000008,
                    0x00020208,
                    0x00020200,
                    0x08000008,
                    0x08020000,
                    0x08000208,
                    0x00000208,
                    0x08020000,
                    0x00020208,
                    0x00000008,
                    0x08020008,
                    0x00020200, },
            { 0x00802001,
                    0x00002081,
                    0x00002081,
                    0x00000080,
                    0x00802080,
                    0x00800081,
                    0x00800001,
                    0x00002001,
                    0x00000000,
                    0x00802000,
                    0x00802000,
                    0x00802081,
                    0x00000081,
                    0x00000000,
                    0x00800080,
                    0x00800001,
                    0x00000001,
                    0x00002000,
                    0x00800000,
                    0x00802001,
                    0x00000080,
                    0x00800000,
                    0x00002001,
                    0x00002080,
                    0x00800081,
                    0x00000001,
                    0x00002080,
                    0x00800080,
                    0x00002000,
                    0x00802080,
                    0x00802081,
                    0x00000081,
                    0x00800080,
                    0x00800001,
                    0x00802000,
                    0x00802081,
                    0x00000081,
                    0x00000000,
                    0x00000000,
                    0x00802000,
                    0x00002080,
                    0x00800080,
                    0x00800081,
                    0x00000001,
                    0x00802001,
                    0x00002081,
                    0x00002081,
                    0x00000080,
                    0x00802081,
                    0x00000081,
                    0x00000001,
                    0x00002000,
                    0x00800001,
                    0x00002001,
                    0x00802080,
                    0x00800081,
                    0x00002001,
                    0x00002080,
                    0x00800000,
                    0x00802001,
                    0x00000080,
                    0x00800000,
                    0x00002000,
                    0x00802080, },
            { 0x00000100,
                    0x02080100,
                    0x02080000,
                    0x42000100,
                    0x00080000,
                    0x00000100,
                    0x40000000,
                    0x02080000,
                    0x40080100,
                    0x00080000,
                    0x02000100,
                    0x40080100,
                    0x42000100,
                    0x42080000,
                    0x00080100,
                    0x40000000,
                    0x02000000,
                    0x40080000,
                    0x40080000,
                    0x00000000,
                    0x40000100,
                    0x42080100,
                    0x42080100,
                    0x02000100,
                    0x42080000,
                    0x40000100,
                    0x00000000,
                    0x42000000,
                    0x02080100,
                    0x02000000,
                    0x42000000,
                    0x00080100,
                    0x00080000,
                    0x42000100,
                    0x00000100,
                    0x02000000,
                    0x40000000,
                    0x02080000,
                    0x42000100,
                    0x40080100,
                    0x02000100,
                    0x40000000,
                    0x42080000,
                    0x02080100,
                    0x40080100,
                    0x00000100,
                    0x02000000,
                    0x42080000,
                    0x42080100,
                    0x00080100,
                    0x42000000,
                    0x42080100,
                    0x02080000,
                    0x00000000,
                    0x40080000,
                    0x42000000,
                    0x00080100,
                    0x02000100,
                    0x40000100,
                    0x00080000,
                    0x00000000,
                    0x40080000,
                    0x02080100,
                    0x40000100, },
            { 0x20000010,
                    0x20400000,
                    0x00004000,
                    0x20404010,
                    0x20400000,
                    0x00000010,
                    0x20404010,
                    0x00400000,
                    0x20004000,
                    0x00404010,
                    0x00400000,
                    0x20000010,
                    0x00400010,
                    0x20004000,
                    0x20000000,
                    0x00004010,
                    0x00000000,
                    0x00400010,
                    0x20004010,
                    0x00004000,
                    0x00404000,
                    0x20004010,
                    0x00000010,
                    0x20400010,
                    0x20400010,
                    0x00000000,
                    0x00404010,
                    0x20404000,
                    0x00004010,
                    0x00404000,
                    0x20404000,
                    0x20000000,
                    0x20004000,
                    0x00000010,
                    0x20400010,
                    0x00404000,
                    0x20404010,
                    0x00400000,
                    0x00004010,
                    0x20000010,
                    0x00400000,
                    0x20004000,
                    0x20000000,
                    0x00004010,
                    0x20000010,
                    0x20404010,
                    0x00404000,
                    0x20400000,
                    0x00404010,
                    0x20404000,
                    0x00000000,
                    0x20400010,
                    0x00000010,
                    0x00004000,
                    0x20400000,
                    0x00404010,
                    0x00004000,
                    0x00400010,
                    0x20004010,
                    0x00000000,
                    0x20404000,
                    0x20000000,
                    0x00400010,
                    0x20004010, },
            { 0x00200000,
                    0x04200002,
                    0x04000802,
                    0x00000000,
                    0x00000800,
                    0x04000802,
                    0x00200802,
                    0x04200800,
                    0x04200802,
                    0x00200000,
                    0x00000000,
                    0x04000002,
                    0x00000002,
                    0x04000000,
                    0x04200002,
                    0x00000802,
                    0x04000800,
                    0x00200802,
                    0x00200002,
                    0x04000800,
                    0x04000002,
                    0x04200000,
                    0x04200800,
                    0x00200002,
                    0x04200000,
                    0x00000800,
                    0x00000802,
                    0x04200802,
                    0x00200800,
                    0x00000002,
                    0x04000000,
                    0x00200800,
                    0x04000000,
                    0x00200800,
                    0x00200000,
                    0x04000802,
                    0x04000802,
                    0x04200002,
                    0x04200002,
                    0x00000002,
                    0x00200002,
                    0x04000000,
                    0x04000800,
                    0x00200000,
                    0x04200800,
                    0x00000802,
                    0x00200802,
                    0x04200800,
                    0x00000802,
                    0x04000002,
                    0x04200802,
                    0x04200000,
                    0x00200800,
                    0x00000000,
                    0x00000002,
                    0x04200802,
                    0x00000000,
                    0x00200802,
                    0x04200000,
                    0x00000800,
                    0x04000002,
                    0x04000800,
                    0x00000800,
                    0x00200002, },
            { 0x10001040,
                    0x00001000,
                    0x00040000,
                    0x10041040,
                    0x10000000,
                    0x10001040,
                    0x00000040,
                    0x10000000,
                    0x00040040,
                    0x10040000,
                    0x10041040,
                    0x00041000,
                    0x10041000,
                    0x00041040,
                    0x00001000,
                    0x00000040,
                    0x10040000,
                    0x10000040,
                    0x10001000,
                    0x00001040,
                    0x00041000,
                    0x00040040,
                    0x10040040,
                    0x10041000,
                    0x00001040,
                    0x00000000,
                    0x00000000,
                    0x10040040,
                    0x10000040,
                    0x10001000,
                    0x00041040,
                    0x00040000,
                    0x00041040,
                    0x00040000,
                    0x10041000,
                    0x00001000,
                    0x00000040,
                    0x10040040,
                    0x00001000,
                    0x00041040,
                    0x10001000,
                    0x00000040,
                    0x10000040,
                    0x10040000,
                    0x10040040,
                    0x10000000,
                    0x00040000,
                    0x10001040,
                    0x00000000,
                    0x10041040,
                    0x00040040,
                    0x10000040,
                    0x10040000,
                    0x10001000,
                    0x10001040,
                    0x00000000,
                    0x10041040,
                    0x00041000,
                    0x00041000,
                    0x00001040,
                    0x00001040,
                    0x00040040,
                    0x10000000,
                    0x10041000, } };

    private final static int s_OP[] = { 0x00000000,
            0x01000000,
            0x00010000,
            0x01010000,
            0x00000100,
            0x01000100,
            0x00010100,
            0x01010100,
            0x00000001,
            0x01000001,
            0x00010001,
            0x01010001,
            0x00000101,
            0x01000101,
            0x00010101,
            0x01010101, };

    // Variables
    private final int [][] m_keyRoundCrypt0 = new int [ DES_ROUNDS ] [ 2 ];

    private final int [][] m_keyRoundCrypt1 = new int [ DES_ROUNDS ] [ 2 ];

    private int m_Flags = 0; // Sundry flags.

    // Use this for normal (single) DES.
    public int SetKey( boolean encrypt,
                       byte [] key )
    {
        m_Flags &= ~ ( DF_3DES | DF_KEYSET );

        int [] keyPermuted = new int [ 2 ];
        if ( !KeyPermute( keyPermuted,
                key ) )
            return 0; // A weak key!

        // Encryption key.
        RoundGen( m_keyRoundCrypt0,
                keyPermuted,
                encrypt );

        m_Flags |= DF_KEYSET;

        return 1;
    }

    // Use this method for triple DES.
    public int SetKey( boolean encrypt,
                       byte [] key1,
                       byte [] key2 )
    {
        /*
         * Method to set 2 keys, thus invoking triple DES mode. Parameters: Encrypt - 0 to decrypt the data, else encrypt it. key1 - the
         * first key, parity bits ignored. key2 - second key, parity bits ignored. Returns: 0 if either key is 'weak' or NULL, else all
         * is well. Notice: key length is 8 bytes
         */
        m_Flags ^= ~ ( DF_3DES | DF_KEYSET );
        int [] keyPermuted = new int [ 2 ];

        if ( !KeyPermute( keyPermuted,
                key1 ) )
            return 0; // A weak key!
        // Encryption key.
        RoundGen( m_keyRoundCrypt0,
                keyPermuted,
                encrypt );

        if ( !KeyPermute( keyPermuted,
                key2 ) )
            return 0; // Another weak key.
        // The second is used for the middle "do other" crypt operation.
        RoundGen( m_keyRoundCrypt1,
                keyPermuted,
                !encrypt );

        // Set flags: key is set, triple DES, and maybe encrypting.
        m_Flags |= ( DF_3DES | DF_KEYSET );

        return 1;
    }

    // Erase the memory holding the key(s).
    void EraseKey()
    {
        // writes zeroes over the key memory
        int i;
        for ( i = 0; i < DES_ROUNDS; i++ )
        {
            m_keyRoundCrypt0[ i ][ 0 ] = m_keyRoundCrypt0[ i ][ 1 ] = 0;
            m_keyRoundCrypt1[ i ][ 0 ] = m_keyRoundCrypt1[ i ][ 1 ] = 0;
        }
        m_Flags &= ~DF_KEYSET;
    }

    /***********************************************************************************************************************************
     * Perform an encryption/decryption operation, which being determined by how the keys were specified. The length must be a multiple
     * of the block size - 64 bits or 8 bytes. Anything else is reject, so it is up to the caller to manage that.
     *
     * Parameters: pvTo - destination pvFrom - source, may be the same as pvTo. cb - number of bytes to transform, multiple of 8.
     * Returns: 0 if size is inappropriate, else all is well, conversion done. ---
     */
    private int Crypt(byte[] pvTo,
                      byte[] pvFrom)
    {

        if ( ( pvFrom.length % DES_BLOCK_BYTES ) != 0 )
            return 0; // Must be a multiple.

        if ( ( m_Flags & DF_KEYSET ) == 0 )
            return 0; // No key - no transform!

        byte [] To = new byte [ DES_BLOCK_BYTES ];
        byte [] From = new byte [ DES_BLOCK_BYTES ];

        int index = 0;
        while ( index < pvFrom.length )
        {
                System.arraycopy(pvFrom, 0 + index, From, 0, DES_BLOCK_BYTES);

            Munge64( To,
                    From );

                System.arraycopy(To, 0, pvTo, 0 + index, DES_BLOCK_BYTES);

            index += DES_BLOCK_BYTES;
        }

        // All is well if we made it this far!
        return 1;
    }

    public int Crypt( byte [] pv )
    {
        return Crypt( pv,
                pv );
    }

    /*
     * +++ CDES::Munge64()
     * 
     * Encrypt/Decrypt 64 bits of data. This is the guts of the algorithm, being the method which does the actual work. This is,
     * perhaps, not as clear as might be desired, but speed is somewhat important here, as there is a great deal of work involved with
     * the actual transformation.
     * 
     * Parameters: pOut - place where data is stored, byte aligned. pIn - source of data, byte aligned, maybe the same as pOut. Returns:
     * Nothing, as failure is not possible. ---
     */
    private void Munge64(byte[] pOut,
                         byte[] pIn)
    {
        int [] Halves = new int [ 2 ];

        // Apply the input transformation.
        int i = 0;
        Halves[ 1 ] = s_IP[ pIn[ i ] & 0x7f ]; /* left halve */
        Halves[ 0 ] = s_IP[ ( pIn[ i++ ] >> 1 ) & 0x7f ]; /* right halve */
        Halves[ 1 ] |= s_IP[ pIn[ i ] & 0x7f ] << 1;
        Halves[ 0 ] |= s_IP[ ( pIn[ i++ ] >> 1 ) & 0x7f ] << 1;
        Halves[ 1 ] |= s_IP[ pIn[ i ] & 0x7f ] << 2;
        Halves[ 0 ] |= s_IP[ ( pIn[ i++ ] >> 1 ) & 0x7f ] << 2;
        Halves[ 1 ] |= s_IP[ pIn[ i ] & 0x7f ] << 3;
        Halves[ 0 ] |= s_IP[ ( pIn[ i++ ] >> 1 ) & 0x7f ] << 3;
        Halves[ 1 ] |= s_IP[ pIn[ i ] & 0x7f ] << 4;
        Halves[ 0 ] |= s_IP[ ( pIn[ i++ ] >> 1 ) & 0x7f ] << 4;
        Halves[ 1 ] |= s_IP[ pIn[ i ] & 0x7f ] << 5;
        Halves[ 0 ] |= s_IP[ ( pIn[ i++ ] >> 1 ) & 0x7f ] << 5;
        Halves[ 1 ] |= s_IP[ pIn[ i ] & 0x7f ] << 6;
        Halves[ 0 ] |= s_IP[ ( pIn[ i++ ] >> 1 ) & 0x7f ] << 6;
        Halves[ 1 ] |= s_IP[ pIn[ i ] & 0x7f ] << 7;
        Halves[ 0 ] |= s_IP[ ( pIn[ i ] >> 1 ) & 0x7f ] << 7;

        /*
         * Perform a circular left shift of both halves in here. The reason is to remove it from the inner loop which follows. Without
         * this step, each iteration would involve another circular shift at the beginning.
         */

        Halves[ 1 ] = ( ( Halves[ 1 ] << 1 ) & 0xfffffffe ) | ( ( Halves[ 1 ] >> 31 ) & 0x00000001 );
        Halves[ 0 ] = ( ( Halves[ 0 ] << 1 ) & 0xfffffffe ) | ( ( Halves[ 0 ] >> 31 ) & 0x00000001 );

        DoDes( Halves,
                m_keyRoundCrypt0 );
        if ( ( m_Flags & DF_3DES ) != 0 )
        {
            // Need to do it some more!
            DoDes( Halves,
                    m_keyRoundCrypt1 );
            DoDes( Halves,
                    m_keyRoundCrypt0 );
        }

        /*
         * Undo the circular left shift which has been in force for this entire loop. It's now time to return data, and unshifted too!
         */

        Halves[ 1 ] = ( ( Halves[ 1 ] >> 1 ) & 0x7fffffff ) | ( ( Halves[ 1 ] << 31 ) & 0x80000000 );
        Halves[ 0 ] = ( ( Halves[ 0 ] >> 1 ) & 0x7fffffff ) | ( ( Halves[ 0 ] << 31 ) & 0x80000000 );

        /*
         * The final bit permutation. This is a little messy, since each output byte contains a bit from each of the 8 bytes we have
         * just permuted.
         */

        int ulL, ulR; // The two output results.

        ulL = s_OP[ Halves[ 1 ] & 0xf ];
        ulR = s_OP[ ( Halves[ 1 ] >> 4 ) & 0xf ];
        ulL |= s_OP[ ( Halves[ 1 ] >> 8 ) & 0xf ] << 2;
        ulR |= s_OP[ ( Halves[ 1 ] >> 12 ) & 0xf ] << 2;
        ulL |= s_OP[ ( Halves[ 1 ] >> 16 ) & 0xf ] << 4;
        ulR |= s_OP[ ( Halves[ 1 ] >> 20 ) & 0xf ] << 4;
        ulL |= s_OP[ ( Halves[ 1 ] >> 24 ) & 0xf ] << 6;
        ulR |= s_OP[ ( Halves[ 1 ] >> 28 ) & 0xf ] << 6;
        ulL |= s_OP[ Halves[ 0 ] & 0xf ] << 1;
        ulR |= s_OP[ ( Halves[ 0 ] >> 4 ) & 0xf ] << 1;
        ulL |= s_OP[ ( Halves[ 0 ] >> 8 ) & 0xf ] << 3;
        ulR |= s_OP[ ( Halves[ 0 ] >> 12 ) & 0xf ] << 3;
        ulL |= s_OP[ ( Halves[ 0 ] >> 16 ) & 0xf ] << 5;
        ulR |= s_OP[ ( Halves[ 0 ] >> 20 ) & 0xf ] << 5;
        ulL |= s_OP[ ( Halves[ 0 ] >> 24 ) & 0xf ] << 7;
        ulR |= s_OP[ ( Halves[ 0 ] >> 28 ) & 0xf ] << 7;

        /* Finally, copy to output area. */

        i = 0;
        pOut[ i++ ] = ( byte ) ( ulL >> 24 );
        pOut[ i++ ] = ( byte ) ( ulL >> 16 );
        pOut[ i++ ] = ( byte ) ( ulL >> 8 );
        pOut[ i++ ] = ( byte ) ulL;

        pOut[ i++ ] = ( byte ) ( ulR >> 24 );
        pOut[ i++ ] = ( byte ) ( ulR >> 16 );
        pOut[ i++ ] = ( byte ) ( ulR >> 8 );
        pOut[ i ] = ( byte ) ulR;

        return;
    }

    protected int IsKeySet()
    {
        return m_Flags & DF_KEYSET;
    }

    /*
     * +++ CDES::DoDes()
     * 
     * Private method to perform the inner most computation of the DES algorithm. This performs one comlete iteration through DES, so
     * data passed in is returned in a completed state. For triple DES, this method would be invoked three times per 64 bit block.
     * Requires some processing of the data before reaching here.
     * 
     * Parameters: pL - the 'left' half of the 64 bits to transform. pR - the 'right' half of the 64 bits to transform. pKey - the key
     * to use for this manipulation. Returns: <nothing, as it cannot fail> ---
     */
    private void DoDes( int [] Halves,
                        int [][] pKey )
    {
        int ii; // Loop index.

        int Left, Right;

        Left = Halves[ 1 ];
        Right = Halves[ 0 ];

        int index = 0;
        for ( ii = 0; ii < DES_ROUNDS / 2; ++ii )
        {
            /*
             * There is no need to perform the expansion permutation here, because we have arranged the data to be in that state
             * already. The expansion takes a 4 bit nibble and includes a bit from each of the adjacent nibbles (with end around carry).
             * The input halves are already adjusted with the end around carry (a 1 bit right circular shift), so by using 6 bits of
             * each byte, we are in fact working with the expanded data, for each byte. By then circular shifting left 4 bits, and again
             * using 6 bits from each byte, we have the remaining half of the expanded data. The key data has been generated to have
             * this same format, hence we can just use an exor, then use the S box substitution. The S box tables are also adjusted with
             * a 1 bit circular shift.
             */

            int NewVal, temp; // Working storage.

            temp = Right ^ pKey[ index ][ 0 ];

            NewVal = s_SB[ 7 ][ temp & 0x3f ];
            NewVal |= s_SB[ 5 ][ ( temp >> 8 ) & 0x3f ];
            NewVal |= s_SB[ 3 ][ ( temp >> 16 ) & 0x3f ];
            NewVal |= s_SB[ 1 ][ ( temp >> 24 ) & 0x3f ];

            // Now for the even numbered operations.
            temp = ( ( Right << 28 ) | ( ( Right >> 4 ) & 0x0fffffff ) ) ^ pKey[ index ][ 1 ];
            NewVal |= s_SB[ 6 ][ temp & 0x3f ];
            NewVal |= s_SB[ 4 ][ ( temp >> 8 ) & 0x3f ];
            NewVal |= s_SB[ 2 ][ ( temp >> 16 ) & 0x3f ];
            NewVal |= s_SB[ 0 ][ ( temp >> 24 ) & 0x3f ];

            Left ^= NewVal; // In phase 2, this is the new right.

            ++index; // Next set of keys.

            temp = Left ^ pKey[ index ][ 0 ];

            NewVal = s_SB[ 7 ][ temp & 0x3f ];
            NewVal |= s_SB[ 5 ][ ( temp >> 8 ) & 0x3f ];
            NewVal |= s_SB[ 3 ][ ( temp >> 16 ) & 0x3f ];
            NewVal |= s_SB[ 1 ][ ( temp >> 24 ) & 0x3f ];

            // Now for the even numbered operations.
            temp = ( ( Left << 28 ) | ( ( Left >> 4 ) & 0x0fffffff ) ) ^ pKey[ index ][ 1 ];
            NewVal |= s_SB[ 6 ][ temp & 0x3f ];
            NewVal |= s_SB[ 4 ][ ( temp >> 8 ) & 0x3f ];
            NewVal |= s_SB[ 2 ][ ( temp >> 16 ) & 0x3f ];
            NewVal |= s_SB[ 0 ][ ( temp >> 24 ) & 0x3f ];

            Right ^= NewVal; // This is now the left part
            ++index;
        }

        // At this stage, left and right are interchanged!
        Halves[ 1 ] = Right;
        Halves[ 0 ] = Left;

        return;
    }

    /*
     * +++ CDES::KeyPermute()
     * 
     * Applies the key permutation operations to the 56 bit input key. Also checks for a weak key, where this is defined as either half
     * being all 1s or all 0s (56 bits in each case). Because these are used in EXOR ops with the input, such values will result in no
     * overall change.
     * 
     * Parameters: pKey - output data. pchKeyIn - the input key, 7 bytes, parity bit removed. Returns: 0 for a weak key, else the key is
     * OK and pKey is set safely. ---
     */
    private boolean KeyPermute( int [] pKey,
                                byte [] achKeyIn )
    {
        pKey[ 1 ] = 0; // Left
        pKey[ 0 ] = 0; // Right

        int ii; // Loop index, of course.

        for ( ii = 0; ii < DES_BITS_IN_KEY; ++ii )
        {
            if ( ( achKeyIn[ s_KP[ ii ] / 8 ] & ( 1 << ( s_KP[ ii ] & 0x7 ) ) ) != 0 )
            {
                // A 1 bit, so need to set it in the key.
                int iIndex; // Which key bit to set.

                iIndex = ( ii >= ( DES_BITS_IN_KEY / 2 ) ) ? ii + 4 : ii;
                pKey[ iIndex / 32 ] |= 1 << ( iIndex % 32 );
            }
        }

        /*
         * Check for a weak key. There is an array of these. There are exactly 64 of them.
         */

        for ( ii = 0; ii < DES_NUM_WEAK_KEYS; ++ii )
        {
            if ( s_KeyWeak[ ii ][ 0 ] == pKey[ 0 ] && s_KeyWeak[ ii ][ 1 ] == pKey[ 1 ] )
            {
                // Bad news - tell the caller.
                return false;
            }
        }

        return true; // Must be OK when we make it this far.

    }

    /*
     * +++
     * 
     * Parameters: pRound - the array to fill with the transformed keys. pKey - . Encrypt - 1 if for encryption, 0 for decryption.
     * Returns: Nothing, failure is not meaningful. ---
     */
    /**
     *
     * Generate the keys used during each round. There are several variations, depending upon encryption/decryption key, and which of
     * the 2 possible keys. The keys are passed in, solving the latter problem.
     *
     *
     * @param pRound
     *            the array to fill with the transformed keys
     * @param pKey
     *            the key to use, in internal 2 x 28 bit format
     * @param encrypt
     *            <code>true</code> for encryption, <code>false</code> for decryption
     */
    private void RoundGen( int [][] pRound,
                           int [] pKey,
                           boolean encrypt )
    {
        int iRound; // Which round number.
        int ii; // Loop index for rounds.

            for ( ii = 0; ii < DES_ROUNDS; ++ii )
        {
            // Which entry gets the value we compute?
            iRound = ( encrypt ) ? ii : ( DES_ROUNDS - 1 - ii );

            // One or two bit shift?
            if ( ( s_KRot & ( 1 << ii ) ) != 0 )
            {
                // Two bit shift if the shift bit array is 1.
                pKey[ 1 ] = ( pKey[ 1 ] << 2 ) | ( ( pKey[ 1 ] >> 26 ) & 0x3 );
                pKey[ 0 ] = ( pKey[ 0 ] << 2 ) | ( ( pKey[ 0 ] >> 26 ) & 0x3 );
            }
            else
            {
                // A zero bit means a one bit shift in the key.
                pKey[ 1 ] = ( pKey[ 1 ] << 1 ) | ( ( pKey[ 1 ] >> 27 ) & 0x1 );
                pKey[ 0 ] = ( pKey[ 0 ] << 1 ) | ( ( pKey[ 0 ] >> 27 ) & 0x1 );
            }

            /*
             * Each round uses just 48 of these 56 bits, so now pick those bits actually used. Note that the following loop(s) actually
             * process all 56 bits. Those bits which are not used are set in one of the unused bits in the output - currently bit 6.
             * This does not affect the operation, as the inner loop uses only the low 6 bits of each byte when feeding data to the
             * S-Box/P-Box tables.
             */

            int jj;

            pRound[ iRound ][ 1 ] = pRound[ iRound ][ 0 ] = 0;

            /*
             * NOTE: This loop is split in two, as it makes the code quite a bit faster, avoiding a division and remainder operation for
             * each iteration.
             */
            for ( jj = 0; jj < ( DES_BITS_IN_KEY / 2 ); ++jj )
            {
                if ( ( pKey[ 1 ] & ( 1 << jj ) ) != 0 )
                {
                    pRound[ iRound ][ s_KPerm[ jj ] / 32 ] |= ( 1 << ( s_KPerm[ jj ] % 32 ) );
                }
            }
            for ( ; jj < DES_BITS_IN_KEY; ++jj )
            {
                if ( ( pKey[ 0 ] & ( 1 << ( jj - DES_BITS_IN_KEY / 2 ) ) ) != 0 )
                {
                    pRound[ iRound ][ s_KPerm[ jj ] / 32 ] |= ( 1 << ( s_KPerm[ jj ] % 32 ) );
                }
            }
        }

        return;
    }
}
