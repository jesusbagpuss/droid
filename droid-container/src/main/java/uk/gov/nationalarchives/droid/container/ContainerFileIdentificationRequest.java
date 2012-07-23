/**
 * Copyright (c) 2012, The National Archives <pronom@nationalarchives.gsi.gov.uk>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following
 * conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 *  * Neither the name of the The National Archives nor the
 *    names of its contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package uk.gov.nationalarchives.droid.container;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import net.domesdaybook.reader.ByteReader;

import uk.gov.nationalarchives.droid.core.interfaces.IdentificationRequest;
import uk.gov.nationalarchives.droid.core.interfaces.RequestIdentifier;
import uk.gov.nationalarchives.droid.core.interfaces.archive.ArchiveFileUtils;
import uk.gov.nationalarchives.droid.core.interfaces.resource.CachedByteArray;
import uk.gov.nationalarchives.droid.core.interfaces.resource.CachedByteArrays;
import uk.gov.nationalarchives.droid.core.interfaces.resource.CachedBytes;
import uk.gov.nationalarchives.droid.core.interfaces.resource.ResourceUtils;
import uk.gov.nationalarchives.droid.core.interfaces.resource.RequestMetaData;

/**
 * @author rflitcroft
 *
 */
public class ContainerFileIdentificationRequest implements IdentificationRequest {

    private static final int BUFFER_CACHE_CAPACITY = 10;

    private static final int CAPACITY = 50 * 1024; // 50 kB
    
    private Long size;
    
    private CachedBytes cachedBinary;

    private File tempFile;

    private int lruCapacity;
    private int bufferCapacity;
    private File tempDir;
    private Log log = LogFactory.getLog(this.getClass());
    
    /**
     * Constructs a new container file resource.
     * @param tempDir the location to write temp files.
     */
    public ContainerFileIdentificationRequest(File tempDir) {
        this(BUFFER_CACHE_CAPACITY, CAPACITY, tempDir);
    }
    
    /**
     * Constructs a new container file resource.
     * @param lruCapacity the buffer cache capacity
     * @param bufferCapacity the buffer capacity
     * @param tempDir the location to write temp files.
     */
    ContainerFileIdentificationRequest(int lruCapacity, int bufferCapacity, File tempDir) {
        
        this.lruCapacity = lruCapacity;
        this.bufferCapacity = bufferCapacity;
        this.tempDir = tempDir;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public final void open(InputStream in) throws IOException {
        /* using normal stream access and CachedByteArrays */
        byte[] firstBuffer = new byte[bufferCapacity];
        int bytesRead = ResourceUtils.readBuffer(in, firstBuffer);
        if (bytesRead < 1) {
            firstBuffer = new byte[0];
            cachedBinary = new CachedByteArray(firstBuffer, 0);
            size = 0L;
        } else if (bytesRead < bufferCapacity) {
            // size the buffer to the amount of bytes available:
            // firstBuffer = Arrays.copyOf(firstBuffer, bytesRead);
            cachedBinary = new CachedByteArray(firstBuffer, bytesRead);
            size = (long) bytesRead;
        } else {
            cachedBinary = new CachedByteArrays(lruCapacity, bufferCapacity, firstBuffer, bufferCapacity);
            tempFile = ArchiveFileUtils.writeEntryToTemp(tempDir, firstBuffer, in);
            cachedBinary.setSourceFile(tempFile);
            size = tempFile.length();
        }
        
        /* using nio and cachedByteBufers
        ReadableByteChannel channel = Channels.newChannel(in);
        ByteBuffer buffer = ByteBuffer.allocate(bufferCapacity);
        
        int bytesRead = 0;
        do {
            bytesRead = channel.read(buffer);
        } while (bytesRead >= 0 && buffer.hasRemaining());
        
        binaryCache = new CachedByteBuffers(lruCapacity, bufferCapacity, buffer);
        if (buffer.limit() == buffer.capacity()) {
            tempFile = ArchiveFileUtils.writeEntryToTemp(tempDir, buffer, channel);
            binaryCache.setSourceFile(tempFile);
            size = tempFile.length();
        } else {
            size = (long) buffer.limit();
        }
        */
    }
    
    /**
     * Releases resources for this resource.
     * @throws IOException if the resource could not be closed
     */
    @Override
    public final void close() throws IOException {
        try {
            if (cachedBinary != null) {
                cachedBinary.close();
                cachedBinary = null;
            }
        } finally {
            if (tempFile != null) {
                if (!tempFile.delete() && tempFile.exists()) {
                    String message = String.format("Could not delete temporary file [%s] for container identification."
                            + "Will try to delete on exit.",
                            tempFile.getAbsolutePath());
                    log.warn(message);
                    // only do this in extreme circumstances, or the app will leak memory until it closes down.
                    tempFile.deleteOnExit(); 
                }
                tempFile = null;
            }
        }
    }
    
    /**
     * Really ensure that temporary files are deleted, if the close method is not called.  
     * Do not rely on this - this is just a double-double safety measure to avoid leaving 
     * temporary files hanging around.
     * {@inheritDoc}
     */
    @Override
    //CHECKSTYLE:OFF
    public void finalize() throws Throwable {
    //CHECKSTYLE:ON
        try {
            close();
        } finally {
            super.finalize();
        }
    }   

    /**
     * {@inheritDoc}
     */
    @Override
    public final byte getByte(long position) {
        return cachedBinary.readByte(position);
    }
        
    /**
     * {@inheritDoc}
     */
    @Override
    public final String getExtension() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final String getFileName() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final long size() {
        return size;
    }

    /**
     * @return the raf
     */
    CachedBytes getCache() {
        return cachedBinary;
    }

    /**
     * {@inheritDoc}
     * @throws IOException 
     */
    @Override
    public final InputStream getSourceInputStream() throws IOException {
        return cachedBinary.getSourceInputStream();
    }

    
    /**
     * {@inheritDoc}
     * @throws IOException 
     */
    @Override
    public final File getSourceFile() throws IOException {
        if (tempFile == null) {
            final InputStream stream = cachedBinary.getSourceInputStream();
            try {
                tempFile = ResourceUtils.createTemporaryFileFromStream(tempDir, stream);
            } finally {
                if (stream != null) {
                    stream.close();
                }
            }
        }
        return tempFile;
    }
    
    
    /**
     * @return the tempFile
     */
    File getTempFile() {
        return tempFile;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public RequestMetaData getRequestMetaData() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RequestIdentifier getIdentifier() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final ByteReader getReader() {
        return cachedBinary;
    }        
}
