package com.example.rmoschenski.dimon_shower;

import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class SendDataThread extends Thread implements I_CompressedBufferAvailable {
    private BlockingQueue<MyOutputBuffer> mFifoOutputBuffers = new LinkedBlockingQueue<>();
    private boolean m_bShouldRun = true;
    private SocketChannel mSocketChannel;

    SendDataThread() {
        m_bShouldRun = true;
        start();
    }

    @Override
    public void CompressedBufferAvailable(MyOutputBuffer myOutputBuffer) {
        try {
            mFifoOutputBuffers.put(myOutputBuffer);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void Stop() {
        m_bShouldRun = false;
        //unblock mFifoOutputBuffers.take() in another thread
        MyOutputBuffer stopMyOutputBuffer = new MyOutputBuffer();
        stopMyOutputBuffer.mbufferID = -2;
        try {
            mFifoOutputBuffers.put(stopMyOutputBuffer);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        try {
            join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void SetSocketChannel(SocketChannel socketChannel) {
        mSocketChannel = socketChannel;
    }

    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public void run() {
        while(m_bShouldRun) {
            MyOutputBuffer myOutputBuffer = null;
            try {
                myOutputBuffer = mFifoOutputBuffers.take();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if(myOutputBuffer.mbufferID < 0)
                continue;

            ByteBuffer bbufCodec = null;

            //send TLV
            try {
                switch (myOutputBuffer.iAudioOrVideo) {
                    case MyOutputBuffer.iBufType_Video:
                    case MyOutputBuffer.iBufType_Audio:
                        bbufCodec = myOutputBuffer.mediaCodec.getOutputBuffer(myOutputBuffer.mbufferID);
                        break;
                    case MyOutputBuffer.iBufType_AudioConfig:
                    case MyOutputBuffer.iBufType_VideoConfig:
                        bbufCodec = myOutputBuffer.mByteBufCodecConfig;
                        /*
                        always flip, in case when we have a previously saved config it is necessary
                        if a config buffer comes from an encoder - does not harm
                         */
                        bbufCodec.rewind();
                        break;
                    default:
                        Log.d("1234", "unknown buffer type");
                }
                int iBufSize = bbufCodec.limit();

                byte[] bufTagAndLen = new byte[5];
                bufTagAndLen[0] = myOutputBuffer.iAudioOrVideo;
                bufTagAndLen[4] = (byte) ((iBufSize & 0xFF000000) >> 24);
                bufTagAndLen[3] = (byte) ((iBufSize & 0x00FF0000) >> 16);
                bufTagAndLen[2] = (byte) ((iBufSize & 0x0000FF00) >> 8);
                bufTagAndLen[1] = (byte) ((iBufSize & 0x000000FF) >> 0);

                /*Log.d("", "i="+myOutputBuffer.mbufferID+" iFrameLen="+
                        iBufSize+" 0x"+bytesToHex(bufTagAndLen)+
                        " dataType="+myOutputBuffer.iAudioOrVideo);*/

                ByteBuffer bbuf = ByteBuffer.allocate(1 + 4 + iBufSize);
                bbuf.put(bufTagAndLen);
                bbuf.put(bbufCodec);
                bbuf.flip();

                /*if(bbufCodec.limit() + 5 != bbuf.limit())
                    Log.d("sdf", "asdf");*/

                while(bbuf.hasRemaining())
                    mSocketChannel.write(bbuf);

            } catch (IllegalStateException ise) {
                ise.printStackTrace();
            } catch (ClosedChannelException cce) {
                //connection lost, do nothing and keep go on, to discard all unsent buffers
            } catch (IOException cce) {
                //connection lost, do nothing and keep go on, to discard all unsent buffers
            } catch (Exception e) {
                e.printStackTrace();
            }
            finally {
                try {
                    if(myOutputBuffer.mediaCodec != null) {
                        myOutputBuffer.mediaCodec.releaseOutputBuffer(myOutputBuffer.mbufferID, true);
                    }
                } catch (IllegalStateException ise) {
                    ise.printStackTrace();
                }
            }
        }
    }
}