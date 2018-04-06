package com.example.rmoschenski.dimon_shower;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CaptureRequest;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;


public class ConnectionLoop implements Runnable {
    private Thread m_thread;

    ServerSocketChannel mServerSocketChannel;
    SocketChannel mSocketChannel;
    ReadableByteChannel mWrappedSocketChannel;
    InputStream mInputStream;
    MainActivity mMainActivity;

    AudioOutgoing mAudioOutgoing = new AudioOutgoing();
    SendDataThread mSendDataThread = null;
    AudioIncoming mAudioIncoming = new AudioIncoming();
    private final byte frame_type_audio = 4, frame_type_audioConfig = 6;

    private boolean mbConnectThreadShouldRun;

    ConnectionLoop(MainActivity mainActivity) {
        mMainActivity = mainActivity;
    }

    /*public boolean IsThreadRunning() {
        return !(m_thread == null);
    }*/

    public void StartConnectionListenerLoop() {
        mbConnectThreadShouldRun = true;
        mSendDataThread = new SendDataThread();
        m_thread = new Thread(this);
        m_thread.start();
    }

    public void StopConnectionListenerLoop() {
        mbConnectThreadShouldRun = false;

        mAudioIncoming.Stop();
        mAudioOutgoing.Stop();
        mMainActivity.mVideoOutgoing.Stop();
        mSendDataThread.Stop();
        mSendDataThread = null;

        if (mServerSocketChannel != null) {
            try {
                mServerSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (mSocketChannel != null) {
            try {
                mSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try {
            if (m_thread != null) {
                mAudioIncoming.mFifoAudioIn.put(-2);
                m_thread.join();
                m_thread = null;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    SocketChannel ListenForHost() throws IOException {
        if (mServerSocketChannel != null) {
            try {
                mServerSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (mSocketChannel != null) {
            try {
                mSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        mServerSocketChannel = ServerSocketChannel.open();
        mServerSocketChannel.socket().setReuseAddress(true);
        mServerSocketChannel.socket().bind(new InetSocketAddress(5001));
        mMainActivity.setMessage("Waiting for a client on TCP port 5001");
        mSocketChannel = mServerSocketChannel.accept();
        mSocketChannel.socket().setSendBufferSize(2 * mMainActivity.GetBitrate() / 8);//send buffer as big as for 2 seconds
        mInputStream = mSocketChannel.socket().getInputStream();
        mSocketChannel.socket().setSoTimeout(1000);
        mWrappedSocketChannel = Channels.newChannel(mInputStream);
        //mSocketChannel.configureBlocking(false);
        mServerSocketChannel.close();
        mServerSocketChannel = null;
        mMainActivity.setMessage("Client connected!");
        return mSocketChannel;
    }

    private void CleanUPSockets() {
        if (mServerSocketChannel != null) {
            try {
                mServerSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mServerSocketChannel = null;
        }

        if (mSocketChannel != null) {
            try {
                mSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean read_exact(byte buf[], int cnt) throws IOException {
        int iToRead = cnt;
        int offset = 0;
        int read;
        while(iToRead > 0)
        {
            read = mInputStream.read(buf, offset, iToRead);
            if(read < 1)
                return false;
            iToRead -= read;
            offset += read;
        }
        return true;
    }

    class TagAndLength {
        byte dataType;
        int iFrameLen;
    }

    TagAndLength ReadDataTypeAndFrameLength() throws Exception {
        byte[] buf = new byte[5];
        if (read_exact(buf, 5)) {
            TagAndLength tagAndLength = new TagAndLength();
            tagAndLength.iFrameLen =
                    (buf[1] & 0xFF) << 0  |
                    (buf[2] & 0xFF) << 8  |
                    (buf[3] & 0xFF) << 16 |
                    (buf[4] & 0xFF) << 24;

            if ((tagAndLength.iFrameLen > 1000000)||(tagAndLength.iFrameLen < 2)) {
                throw new Exception("ReadFrameLength iFrameLen=%d" + tagAndLength.iFrameLen);
            }
            tagAndLength.dataType = buf[0];
            return tagAndLength;
        }
        throw new Exception("read_exact read < 1");
    }

    public void run() {
        boolean bFirstConnect = true;

        CaptureRequest myCaptureRequest = mMainActivity.mRecordingRequestBuilder.build();

        while (mbConnectThreadShouldRun) {
            try {
                SocketChannel socketChannel = ListenForHost();
                mSendDataThread.SetSocketChannel(socketChannel);

                if(bFirstConnect) {
                    mAudioOutgoing.Start(mSendDataThread);
                    mMainActivity.mVideoOutgoing.Start(mSendDataThread);
                    mAudioIncoming.Start();
                    bFirstConnect = false;
                }
                else {
                    mAudioOutgoing.AfterReconnect();
                    mMainActivity.mVideoOutgoing.AfterReconnect();
                }

                try {
                    mMainActivity.mCameraCaptureSession.setRepeatingRequest(myCaptureRequest, null, null);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }

                while(mbConnectThreadShouldRun) {
                    try {
                        TagAndLength tagAndLength = ReadDataTypeAndFrameLength();

                        switch(tagAndLength.dataType) {
                            case frame_type_audio:
                            case frame_type_audioConfig:
                                break;
                            default:
                                Log.d("---------",
                                        "Unknown dataType=" + tagAndLength.dataType + " must be=" + frame_type_audio + "or" + frame_type_audioConfig);
                        }

                        //Log.d("---------", "iFrameLen=" + iFrameLen);
                        int inputBufferId;
                        try {
                            inputBufferId = mAudioIncoming.mFifoAudioIn.take();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            break;
                        }
                        if (-2 == inputBufferId) {
                            mbConnectThreadShouldRun = false;
                            break;//stop the thread
                        }
                        try {
                            ByteBuffer inputBuffer = mAudioIncoming.mMediaCodecAudioDecoder.getInputBuffer(inputBufferId);
                            inputBuffer.limit(tagAndLength.iFrameLen);

                            while(inputBuffer.position() < tagAndLength.iFrameLen) {
                                if(mWrappedSocketChannel.read(inputBuffer) <= 0)
                                    throw new IOException();
                            }
                        }catch (IllegalStateException ex) {
                            mMainActivity.setMessage("IllegalStateException in AudioIncoming codec");
                            break;
                        }catch (IOException e) {
                            mMainActivity.setMessage(e.getMessage());
                            break;
                        }
                        mAudioIncoming.mMediaCodecAudioDecoder.queueInputBuffer(inputBufferId, 0, tagAndLength.iFrameLen, 0, 0);

                    } catch(SocketTimeoutException sex) {
                        mMainActivity.setMessage("SocketTimeoutException");
                        mMainActivity.mCameraCaptureSession.stopRepeating();
                        mAudioOutgoing.Pause();
                        break;
                    } catch (Exception ex) {
                        mMainActivity.setMessage(ex.getMessage());
                        mMainActivity.mCameraCaptureSession.stopRepeating();
                        mAudioOutgoing.Pause();
                        break;
                    }
                }
            } catch (Exception e) {
                mMainActivity.setMessage(e.getMessage());
            }
        } //while (mbConnectThreadShouldRun) {

        mAudioOutgoing.Stop();
        if(mMainActivity.mCameraCaptureSession != null)
            mMainActivity.mCameraCaptureSession.close();

        if(mMainActivity.mCameraDevice != null)
            mMainActivity.mCameraDevice.close();

        mMainActivity.setMessage("Idle...");
    }
}
