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
import android.os.StrictMode;
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
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Scanner;

public class MainActivity extends Activity implements SurfaceHolder.Callback, AdapterView.OnItemSelectedListener {

    private SurfaceView mPreviewView;
    private Spinner mResolutionSpinner, mCameraNumSpinner, mAVC_HEVC;
    private EditText mEditTextBitrate;
    private Button mButOnOff;

    CameraDevice mCameraDevice = null;
    public TextView textViewStatus;
    public CameraCaptureSession mCameraCaptureSession = null;
    public CaptureRequest.Builder mRecordingRequestBuilder;

    String mStrCameraToUse;
    private ArrayList<View> views_to_fade = new ArrayList<>();
    private boolean m_b_UsePreview = true;
    MainActivity mMainActivity;
    public static final int MULTIPLE_PERMISSIONS = 10;
    ConnectionLoop mConnectionLoop = null;
    VideoOutgoing mVideoOutgoing = new VideoOutgoing();

    Queue<String> mQueueString = new ArrayDeque<>();
    private TextView messageView;

    public void setMessage(final String str) {
        final int iLine = Thread.currentThread().getStackTrace()[3].getLineNumber();
        runOnUiThread(new Runnable() {
            public void run() {
                if(mQueueString.size() > 12)
                    mQueueString.remove();
                mQueueString.add(str + ":" + iLine);

                messageView.setText("");
                for(String mystr : mQueueString)
                    messageView.append(mystr + "\n");
                messageView.setVisibility(View.VISIBLE);
            }
        });
    }

    String[] permissions= new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA};
    private  boolean checkPermissions() {
        int result;
        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            result = ContextCompat.checkSelfPermission(this, p);
            if (result != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }
        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[listPermissionsNeeded.size()]),MULTIPLE_PERMISSIONS );
            return false;
        }
        return true;
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case MULTIPLE_PERMISSIONS: {
                for (int i : grantResults) {
                    if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {

                    }
                }
            }
        }
    }

    public int GetBitrate() {
        return Integer.valueOf(mEditTextBitrate.getText().toString());
    }

    private void InitCamerasSpinner() {
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

    private void InitResolutionsSpinner() {
        if (mStrCameraToUse.isEmpty())
            return;
        mResolutionSpinner = (Spinner) findViewById(R.id.spinner_resolution);
        List<String> strResolutions = new ArrayList<>();
        CameraManager mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            Size[] mSizes = mCameraManager.getCameraCharacteristics(mStrCameraToUse).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(SurfaceHolder.class);
            for (Size sz : mSizes)
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

    private void InitCodecSpinner() {
        views_to_fade.add(mAVC_HEVC = findViewById(R.id.spinner_AVC_HEVC));
        List<String> strCodecs = new ArrayList<>();

        strCodecs.add("avc");
        strCodecs.add("hevc");
        ArrayAdapter<String> codecAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, strCodecs);
        codecAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mAVC_HEVC.setAdapter(codecAdapter);
    }

    private void OpenAndStartCamera() {
        mMainActivity = this;
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraManager.openCamera(mCameraNumSpinner.getSelectedItem().toString(), new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    mCameraDevice = camera;
                    Scanner scanner = new Scanner(mResolutionSpinner.getSelectedItem().toString()).useDelimiter("x");
                    Surface recordingSurface = mVideoOutgoing.Init(scanner.nextInt(), scanner.nextInt(), GetBitrate(), "video/" + mAVC_HEVC.getSelectedItem().toString());
                    scanner.close();

                    try {
                        mRecordingRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                        return;
                    }
                    mRecordingRequestBuilder.addTarget(recordingSurface);
                    List<Surface> outputsSurfaces = new ArrayList<>();
                    outputsSurfaces.add(recordingSurface);
                    if (m_b_UsePreview) {
                        outputsSurfaces.add(mPreviewView.getHolder().getSurface());
                        mRecordingRequestBuilder.addTarget(mPreviewView.getHolder().getSurface());
                    }

                    try {
                        camera.createCaptureSession(outputsSurfaces, new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                Log.i("", "capture session configured: " + session);
                                mCameraCaptureSession = session;

                                if(null == mConnectionLoop) {
                                    mConnectionLoop = new ConnectionLoop(mMainActivity);
                                    mConnectionLoop.StartConnectionListenerLoop();
                                }
                                else
                                    Log.e("error", "mConnectionLoop.IsThreadRunning()" + session);
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                Log.e("error", "capture session configure failed: " + session);
                                mCameraCaptureSession = null;
                            }
                        }, null);
                    } catch (CameraAccessException e) {
                        Log.e("error", "couldn't create capture session for camera: " + camera.getId(), e);
                        mCameraCaptureSession = null;
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    Log.d("onDisconnected", "deviceCallback.onDisconnected() start");
                    if (null != mConnectionLoop) {
                        mConnectionLoop.StopConnectionListenerLoop();
                        mConnectionLoop = null;
                    }
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.d("onError", "deviceCallback.onError() start");
                }

            }, null);
        } catch (CameraAccessException e) {
            Log.e("", "CameraAccessException, couldn't open camera", e);
        } catch (SecurityException e) {
            Log.e("", "SecurityException, couldn't open camera", e);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if(!checkPermissions())
            return;

        //Allow network operations on GUI thread
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        setContentView(R.layout.activity_main);
        mPreviewView = findViewById(R.id.preview_view);
        mPreviewView.getHolder().addCallback(this);
        messageView = findViewById(R.id.textViewStatus);

        InitCamerasSpinner();
        InitCodecSpinner();

        views_to_fade.add(mEditTextBitrate = findViewById(R.id.bitrate));

        mButOnOff = findViewById(R.id.button_on_off);
        mButOnOff.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String str = mButOnOff.getText().toString();
                if (str.equals("Turn off")) {
                    if (null != mConnectionLoop) {
                        mConnectionLoop.StopConnectionListenerLoop();
                        mConnectionLoop = null;
                    }

                    for (View view : views_to_fade)
                        view.setEnabled(true);

                    mButOnOff.setText("Turn on");
                } else {
                    for (View view : views_to_fade)
                        view.setEnabled(false);
                    mButOnOff.setText("Turn off");
                    OpenAndStartCamera();
                }
            }
        });
        textViewStatus = findViewById(R.id.textViewStatus);

        for (View view : views_to_fade)
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

    /**
     * SurfaceHolder.Callback methods
     */
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
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