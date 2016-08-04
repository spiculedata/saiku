package org.saiku.datasources.connection.encrypt;

import org.springframework.dao.DataAccessException;

/**
 * Hello world!
 *
 */
class TripleDesPasswordEncoder {

    private final static byte[] defaultKey1 = { ( byte ) 0xa9,
            ( byte ) 0xa9,
            ( byte ) 0x0f,
            ( byte ) 0xb4,
            ( byte ) 0x57,
            ( byte ) 0x11,
            ( byte ) 0x8d,
            ( byte ) 0x2c };

    private final static byte[] defaultKey2 = { ( byte ) 0x2c,
            ( byte ) 0x2c,
            ( byte ) 0xf4,
            ( byte ) 0x5c,
            ( byte ) 0x75,
            ( byte ) 0x15,
            ( byte ) 0xc6,
            ( byte ) 0xa3 };

    private Des des;


    /**
     * {@inheritDoc}
     */
    public String encode( String rawPass)
            throws DataAccessException
    {
        Object salt = null;
        // The password may be empty. Not recommended, but.....
        if ( rawPass.length() == 0 )
        {
            return rawPass;
        }

        // We encrypt the NUL to simplify decryption
        int length = rawPass.length() + 1;

        // Make the input string length a multiple of DES_BLOCK_BYTES bytes
        if ( ( length % Des.DES_BLOCK_BYTES ) != 0 )
            length = ( ( length / Des.DES_BLOCK_BYTES ) * Des.DES_BLOCK_BYTES ) + Des.DES_BLOCK_BYTES;

        byte [] source = rawPass.getBytes();
        byte [] digest = new byte [ length ];

      System.arraycopy(source, 0, digest, 0, source.length);

        // Initialize the encryption keys
        setKeys( true,
                salt );

        // Encrypt the password
        getEncoder().Crypt( digest );

        // Convert the encrypted data to ASCII HEX
        StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < length; i++ )
        {
            int temp = ( int ) digest[ i ] & 0x000000ff;
            if ( temp < 16 )
                sb.append( '0' );
            sb.append( Integer.toHexString( temp ) );
        }

        return sb.toString();
    }

    private void setKeys(boolean encrypt,
                         Object timestamp)
    {
        getEncoder().SetKey( encrypt,
                defaultKey1,
                defaultKey2 );
    }

    private Des getEncoder()
    {
        if ( des == null )
        {
            des = new Des();
        }
        return des;
    }

}