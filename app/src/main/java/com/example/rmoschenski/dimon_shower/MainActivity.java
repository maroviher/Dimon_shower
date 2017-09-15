package com.example.rmoschenski.dimon_shower;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class MainActivity extends Activity implements SurfaceHolder.Callback, AdapterView.OnItemSelectedListener {

    private static final int iCameraPermissionID = 1234;
    private SurfaceView mPreviewView;
    private Spinner mResolutionSpinner, mCameraNumSpinner, mAVC_HEVC;
    private EditText mEditTextBitrate;
    private Button mButOnOff;

    CameraDevice mCamera = null;
    public TextView textViewStatus;
    public CameraCaptureSession mSession = null;
    public CaptureRequest.Builder mRecordingRequestBuilder;

    String mStrCameraToUse = "";
    private ArrayList<View> views_to_fade = new ArrayList<>();

    ServerSocketChannel mServerSocketChannel;
    private Thread mThreadVideo = null;
    private boolean mbThreadShouldRun = true;
    private MediaCodec mEncoder;
    private boolean m_b_UsePreview = true;

    private int GetBitrate()
    {
        return Integer.valueOf(mEditTextBitrate.getText().toString());
    }

    private void InitCamerasSpinner()
    {
        mCameraNumSpinner = (Spinner) findViewById(R.id.spinner_camera_num);
        CameraManager mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] cameras = mCameraManager.getCameraIdList();
            ArrayAdapter<String> camerasAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, cameras);
            camerasAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mCameraNumSpinner.setAdapter(camerasAdapter);
            mCameraNumSpinner.setOnItemSelectedListener(this);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        views_to_fade.add(mCameraNumSpinner);
    }

    private Surface configureMediaCodecEncoder() {
        String strCodec = "video/" + mAVC_HEVC.getSelectedItem().toString();
        Scanner scanner = new Scanner(mResolutionSpinner.getSelectedItem().toString()).useDelimiter("x");
        int x = scanner.nextInt();
        int y = scanner.nextInt();
        scanner.close();
        MediaFormat format = MediaFormat.createVideoFormat(strCodec, x, y);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, GetBitrate());
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        //format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 100);
        try {
            mEncoder = MediaCodec.createEncoderByType(strCodec);
        } catch (IOException ioe) {
            throw new IllegalStateException("failed to create video/avc encoder", ioe);
        }
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        return mEncoder.createInputSurface();
    }

    private void InitResolutionsSpinner()
    {
        if(mStrCameraToUse.isEmpty())
            return;
        mResolutionSpinner = (Spinner) findViewById(R.id.spinner_resolution);
        List<String> strResolutions = new ArrayList<>();
        CameraManager mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] cameras = mCameraManager.getCameraIdList();

            Size[] mSizes = mCameraManager.getCameraCharacteristics(mStrCameraToUse).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(SurfaceHolder.class);
            for(Size sz : mSizes)
                strResolutions.add(sz.toString());
            ArrayAdapter<String> codecAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, strResolutions);
            codecAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mResolutionSpinner.setAdapter(codecAdapter);
            mResolutionSpinner.setSelection(codecAdapter.getPosition("1920x1080"));
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        mResolutionSpinner.setEnabled(false);
        views_to_fade.add(mResolutionSpinner);
    }

    private void InitCodecSpinner()
    {
        views_to_fade.add(mAVC_HEVC = findViewById(R.id.spinner_AVC_HEVC));
        List<String> strCodecs = new ArrayList<>();

        strCodecs.add("avc");
        strCodecs.add("hevc");
        ArrayAdapter<String> codecAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, strCodecs);
        codecAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mAVC_HEVC.setAdapter(codecAdapter);
    }
    @Override
    public void onRequestPermissionsResult(int i, String permissions[], int[] grantResults)
    {
        if(i == iCameraPermissionID)
            OpenAndStartCamera();
    }

    private void OpenAndStartCamera()
    {
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED)
        {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, iCameraPermissionID);
            return;
        }

        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraManager.openCamera(mCameraNumSpinner.getSelectedItem().toString(), new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    mCamera = camera;
                    Surface recordingSurface = configureMediaCodecEncoder();

                    try {
                        mRecordingRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                        return;
                    }
                    mRecordingRequestBuilder.addTarget(recordingSurface);
                    List<Surface> outputsSurfaces = new ArrayList<>();
                    outputsSurfaces.add(recordingSurface);
                    if(m_b_UsePreview) {
                        outputsSurfaces.add(mPreviewView.getHolder().getSurface());
                        mRecordingRequestBuilder.addTarget(mPreviewView.getHolder().getSurface());
                    }

                    try {
                        camera.createCaptureSession(outputsSurfaces, new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                Log.i("", "capture session configured: " + session);
                                mSession = session;
                                mThreadVideo.start();
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                Log.e("", "capture session configure failed: " + session);
                            }
                        }, null);
                    } catch (CameraAccessException e) {
                        Log.e("", "couldn't create capture session for camera: " + camera.getId(), e);
                        return;
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    Log.d("", "deviceCallback.onDisconnected() start");
                    mSession = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.d("", "deviceCallback.onError() start");
                }

            }, null);
        } catch (CameraAccessException e) {
            Log.e("", "CameraAccessException, couldn't open camera", e);
        }
        catch (SecurityException e) {
            Log.e("", "SecurityException, couldn't open camera", e);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main);
        mPreviewView = findViewById(R.id.preview_view);
        mPreviewView.getHolder().addCallback(this);

        InitCamerasSpinner();
        InitCodecSpinner();

        views_to_fade.add(mEditTextBitrate = findViewById(R.id.bitrate));

        mButOnOff = findViewById(R.id.button_on_off);
        mButOnOff.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String str = mButOnOff.getText().toString();
                if(str.equals("Turn off"))
                {
                    if(mServerSocketChannel != null) {
                        try {
                            mServerSocketChannel.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                    mbThreadShouldRun = false;
                    if(mThreadVideo != null) {
                        try {
                            mThreadVideo.join();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            return;
                        }
                    }

                    for(View view : views_to_fade)
                        view.setEnabled(true);

                    mButOnOff.setText("Turn on");
                }
                else
                {
                    for(View view : views_to_fade)
                        view.setEnabled(false);
                    mButOnOff.setText("Turn off");
                    mbThreadShouldRun = true;
                    OpenAndStartCamera();
                }
            }
        });
        textViewStatus = findViewById(R.id.textViewStatus);

        mThreadVideo = new Thread() {

            private SocketChannel mSocketChannel;

            void setMessage(String str)
            {
                final String _str = str;

                runOnUiThread(new Runnable() {
                    public void run() {
                        textViewStatus.setText(_str);
                    }
                });
            }

            void ListenForHost() throws Exception
            {
                mServerSocketChannel = ServerSocketChannel.open();
                mServerSocketChannel.socket().setReuseAddress(true);
                mServerSocketChannel.socket().bind(new InetSocketAddress(5001));
                setMessage("Waiting for a client on TCP port 5001");
                mSocketChannel = mServerSocketChannel.accept();
                mSocketChannel.socket().setSendBufferSize(3*GetBitrate()/8);//send buffer as big as for 3 seconds
                mSocketChannel.configureBlocking(false);
                mServerSocketChannel.close();
                mServerSocketChannel=null;
                setMessage("Client connected!");
            }

            private void CleanUPSockets()
            {
                if(mServerSocketChannel != null) {
                    try {
                        mServerSocketChannel.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    mServerSocketChannel = null;
                }

                if(mSocketChannel != null) {
                    try {
                        mSocketChannel.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            private byte[] IntToByteArr(int i)
            {
                byte[] bytes = new byte[4];
                bytes[3] = (byte) ((i & 0xFF000000) >> 24);
                bytes[2] = (byte) ((i & 0x00FF0000) >> 16);
                bytes[1] = (byte) ((i & 0x0000FF00) >> 8);
                bytes[0] = (byte) ((i & 0x000000FF) >> 0);
                return bytes;
            }

            @Override
            public void run() {

                boolean bGo = true;

                ByteBuffer bbufConfig = null;

                CaptureRequest myCaptureRequest = mRecordingRequestBuilder.build();
                mEncoder.start();
                while(bGo && mbThreadShouldRun){
                    try {
                        ListenForHost();

                        //after reconnect first send a buffer with codec config saved before
                        if(bbufConfig != null)
                        {
                            byte[] bytes = IntToByteArr(bbufConfig.limit());
                            mSocketChannel.write(ByteBuffer.wrap(bytes));
                            mSocketChannel.write(bbufConfig);
                            bbufConfig.rewind();

                            //request a key-frame
                            Bundle b = new Bundle();
                            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                            mEncoder.setParameters(b);
                        }

                        mSession.setRepeatingRequest(myCaptureRequest, null, null);

                        MediaCodec.BufferInfo bufInfo = new MediaCodec.BufferInfo();
                        boolean bError = false;
                        while (!bError && mbThreadShouldRun) {
                            int outputBufferId = mEncoder.dequeueOutputBuffer(bufInfo, -1);
                            if (outputBufferId >= 0) {

                                ByteBuffer outputBuffer = mEncoder.getOutputBuffer(outputBufferId);

                                try {
                                    int iFrameLen = outputBuffer.limit();
                                    byte[] bytes = IntToByteArr(iFrameLen);
                                    ByteBuffer bbufLen = ByteBuffer.wrap(bytes);
                                    switch (bufInfo.flags)
                                    {
                                        case 0://normal frame
                                            break;
                                        case 1://BUFFER_FLAG_KEY_FRAME
                                            break;
                                        case 2://BUFFER_FLAG_CODEC_CONFIG
                                            bbufConfig = ByteBuffer.allocate(outputBuffer.capacity());
                                            outputBuffer.rewind();
                                            bbufConfig.put(outputBuffer);
                                            outputBuffer.rewind();
                                            bbufConfig.flip();
                                            break;
                                    }
                                    int iSent = mSocketChannel.write(bbufLen);
                                    iSent += mSocketChannel.write(outputBuffer);
                                    if(iSent != iFrameLen + 4)
                                    {
                                        Log.d("--------", "overflow");
                                        bError = true;
                                    }
                                } catch (IOException e) {
                                    Log.d("--------", "IOException");
                                    bError = true;
                                }
                                mEncoder.releaseOutputBuffer(outputBufferId, false);
                            }
                        }
                    }
                    catch (java.nio.channels.AsynchronousCloseException ex2)//listen socket was closed by stop button
                    {
                        bGo = false;
                    }
                    catch (Exception e) {
                        setMessage(e.getMessage());
                        e.printStackTrace();
                    }
                    try {
                        mSession.stopRepeating();
                        mEncoder.flush();
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                    CleanUPSockets();

                }//while(bGo && mbThreadShouldRun){
                mSession.close();
                mCamera.close();
                setMessage("Idle...");
            }
        };
        for(View view : views_to_fade)
            view.setEnabled(false);
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }


    /** SurfaceHolder.Callback methods */
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if((mThreadVideo != null) && (!mThreadVideo.isAlive()))
            OpenAndStartCamera();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }



    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
        mStrCameraToUse = adapterView.getItemAtPosition(i).toString();
        InitResolutionsSpinner();
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {

    }
}
