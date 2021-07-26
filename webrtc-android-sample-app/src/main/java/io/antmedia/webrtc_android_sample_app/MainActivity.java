package io.antmedia.webrtc_android_sample_app;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONException;
import org.json.JSONObject;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoFrame;
import org.webrtc.voiceengine.NewFrameListioner;
import org.webrtc.voiceengine.NewNetworkTextureListioner;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

import de.tavendo.autobahn.WebSocket;
import io.antmedia.webrtcandroidframework.IDataChannelObserver;
import io.antmedia.webrtcandroidframework.IWebRTCClient;
import io.antmedia.webrtcandroidframework.IWebRTCListener;
import io.antmedia.webrtcandroidframework.StreamInfo;
import io.antmedia.webrtcandroidframework.WebRTCClient;
import io.antmedia.webrtcandroidframework.apprtc.CallActivity;

import static io.antmedia.webrtcandroidframework.apprtc.CallActivity.EXTRA_CAPTURETOTEXTURE_ENABLED;
import static io.antmedia.webrtcandroidframework.apprtc.CallActivity.EXTRA_DATA_CHANNEL_ENABLED;
import static io.antmedia.webrtcandroidframework.apprtc.CallActivity.EXTRA_VIDEO_BITRATE;
import static io.antmedia.webrtcandroidframework.apprtc.CallActivity.EXTRA_VIDEO_FPS;
import static org.opencv.core.CvType.CV_8UC1;
import static org.opencv.imgproc.Imgproc.cvtColor;

public class MainActivity extends Activity implements IWebRTCListener, IDataChannelObserver {

    /**
     * Change this address with your Ant Media Server address
     */
    public static final String SERVER_ADDRESS = "172.105.36.192:5080";

    /**
     * Mode can Publish, Play or P2P
     */

    private String webRTCMode = IWebRTCClient.MODE_PUBLISH;

    private boolean enableDataChannel = true;

    public static final String SERVER_URL = "ws://" + SERVER_ADDRESS + "/WebRTCAppEE/websocket";
    public static final String REST_URL = "http://" + SERVER_ADDRESS + "/WebRTCAppEE/rest/v2";

    private WebRTCClient webRTCClient;

    private Button startStreamingButton;
    private String operationName = "";
    private String streamId;

    private SurfaceViewRenderer cameraViewRenderer;
    private SurfaceViewRenderer pipViewRenderer;
    private Spinner streamInfoListSpinner;

    // variables for handling reconnection attempts after disconnected
    final int RECONNECTION_PERIOD_MLS = 1000000;
    private boolean stoppedStream = false;
    Handler reconnectionHandler = new Handler();
    Runnable reconnectionRunnable = new Runnable() {
        @Override
        public void run() {
            if (!webRTCClient.isStreaming()) {
                attempt2Reconnect();
                // call the handler again in case startStreaming is not successful
                reconnectionHandler.postDelayed(this, RECONNECTION_PERIOD_MLS);
            }
        }
    };
    private String TAG = "MainActivity";
    TextureView LocalTextureView, NetworkTextureView;
    private int i = 0;
    private int j = 0;


    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            this.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN |
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN |
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        } catch (Exception e) {
            e.printStackTrace();
        }

        NetworkTextureView = findViewById(R.id.network_texture_view);
        LocalTextureView = findViewById(R.id.local_texture_view);
        cameraViewRenderer = findViewById(R.id.camera_view_renderer);
        pipViewRenderer = findViewById(R.id.pip_view_renderer);
        startStreamingButton = findViewById(R.id.start_streaming_button);
        streamInfoListSpinner = findViewById(R.id.stream_info_list);

        if (!webRTCMode.equals(IWebRTCClient.MODE_PLAY)) {
            streamInfoListSpinner.setVisibility(View.INVISIBLE);
        } else {
            streamInfoListSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                boolean firstCall = true;

                @Override
                public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                    //for some reason in android onItemSelected is called automatically at first.
                    //there are some discussions about it in stackoverflow
                    //so we just have simple check
                    if (firstCall) {
                        firstCall = false;
                        return;
                    }
                    webRTCClient.forceStreamQuality(Integer.parseInt((String) adapterView.getSelectedItem()));
                    Log.i("MainActivity", "Spinner onItemSelected");
                }

                @Override
                public void onNothingSelected(AdapterView<?> adapterView) {

                }
            });
        }

        // Check for mandatory permissions.
        for (String permission : CallActivity.MANDATORY_PERMISSIONS) {
            if (this.checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission " + permission + " is not granted", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if (webRTCMode.equals(IWebRTCClient.MODE_PUBLISH)) {
            startStreamingButton.setText("Start Publishing");
            operationName = "Publishing";
        } else if (webRTCMode.equals(IWebRTCClient.MODE_PLAY)) {
            startStreamingButton.setText("Start Playing");
            operationName = "Playing";
        } else if (webRTCMode.equals(IWebRTCClient.MODE_JOIN)) {
            startStreamingButton.setText("Start P2P");
            operationName = "P2P";
        }
        this.getIntent().putExtra(EXTRA_CAPTURETOTEXTURE_ENABLED, true);
        this.getIntent().putExtra(EXTRA_VIDEO_FPS, 30);
        this.getIntent().putExtra(EXTRA_VIDEO_BITRATE, 1500);
        this.getIntent().putExtra(EXTRA_CAPTURETOTEXTURE_ENABLED, true);
        this.getIntent().putExtra(EXTRA_DATA_CHANNEL_ENABLED, enableDataChannel);

        webRTCClient = new WebRTCClient(this, this);

        webRTCClient.setListioner(new NewFrameListioner() {
            @Override
            public void onNewFrame(VideoFrame frame) {

            }

            @Override
            public void onNewTexture(SurfaceTexture texture) {
                Log.d(TAG, "New Texture By WebRTC ");
//                if (i == 0) {
//                    i = 1;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        LocalTextureView.setSurfaceTexture(texture);
                    }
                });
                texture.detachFromGLContext();
                //}

                //newSurfaceView.setVisibility(View.VISIBLE);
                // newSurfaceView.resume();
                //  previewFrameTexture = texture;
//                runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        texture1.setSurfaceTexture(texture);
//                    }
//                });
                // texture.detachFromGLContext();

                ///             surfaceTexture = texture;
//
//                if (i == 0) {
//                    previewFrameTexture = texture;
//                    i = 1;
//                    runOnUiThread(new Runnable() {
//                        @Override
//                        public void run() {
//                            texture1.setSurfaceTexture(texture);
//                        }
//                    });
//                    // texture.release();
//                    texture.detachFromGLContext();
//                } else {
//                    surfaceTexture = texture;
//                    i = 0;
//                    runOnUiThread(new Runnable() {
//                        @Override
//                        public void run() {
//                            texture2.setSurfaceTexture(texture);
//                           // texture.detachFromGLContext();
//                        }
//                    });
//                }
                // texture.release();
                //texture.detachFromGLContext();

//                if(texture.isReleased())
//                 texture.detachFromGLContext();
//                previewDisplayView.setVisibility(View.VISIBLE);
//                previewFrameTexture = texture;

            }
            //  }
        });

        webRTCClient.setNetworkTextureListioner(new NewNetworkTextureListioner() {
            @Override
            public void onNewNetworkTexture(SurfaceTexture texture) {
                Log.d(TAG, "New Texture From Network");
                //previewFrameTexture = texture;
                if (j == 0) {
                    j = 1;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            NetworkTextureView.setSurfaceTexture(texture);
                        }
                    });
                    texture.detachFromGLContext();

                }

            }
        });

        // eglManager = new EglManager(null);
//
//        try{
//
//            // Mediapipe Processor
//            processor = new MultiInputFrameProcessor(
//                    this,
//                    eglManager.getNativeContext(),
//                    BINARY_GRAPH_NAME,
//                    INPUT_VIDEO_STREAM_NAME,
//                    OUTPUT_VIDEO_STREAM_NAME, BG_VIDEO_INPUT_STREAM);
//
//        }catch (Exception e)
//        {
//
//        }
//
//
//        processor.getVideoSurfaceOutput().setFlipY(true);
//        processor.getGraph().addPacketCallback(OUTPUT_VIDEO_STREAM_NAME, new PacketCallback() {
//            @Override
//            public void process(Packet packet) {
//                //Log.d("FRAME_TEST", "New frame " + hasImin);
//                long curTime = System.currentTimeMillis();
//              //  Log.d("RUNTIME>>", curTime - oldTime + "MS");
//               // run_logs = run_logs + (curTime - oldTime) + " MS\n ";
//                Log.d("RUNTIME>>", curTime - oldTime + "MS");
//                oldTime = curTime;
//
////                if (!hasImin) {
////                    Log.d("FRAME_TEST_i", "Condition true");
////                    hasImin = true;
////                    directionMessage();
////                }
//            }
//        });


        //webRTCClient.setOpenFrontCamera(false);

        streamId = "stream1";
        String tokenId = "tokenId";
        webRTCClient.setVideoRenderers(pipViewRenderer, cameraViewRenderer);

//        cameraViewRenderer.getHolder().addCallback(new SurfaceHolder.Callback() {
//            @Override
//            public void surfaceCreated(SurfaceHolder surfaceHolder) {
//                processor.getVideoSurfaceOutput().setSurface(surfaceHolder.getSurface());
//
//            }
//
//            @Override
//            public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int width, int height) {
//
//                // (Re-)Compute the ideal size of the camera-preview display (the area that the
//                // camera-preview frames get rendered onto, potentially with scaling and rotation)
//                // based on the size of the SurfaceView that contains the display.
//               // Size viewSize = new Size(width, height);
//               // Size displaySize = cameraHelper.computeDisplaySizeFromViewSize(viewSize);
//
//                // Connect the converter to the camera-preview frames as its input (via
//                // previewFrameTexture), and configure the output width and height as the computed
//                // display size.
//                converter.setSurfaceTextureAndAttachToGLContext(
//                        previewFrameTexture,width, height);
//
//            }
//
//            @Override
//            public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
//                processor.getVideoSurfaceOutput().setSurface(null);
//            }
//        });

        cameraViewRenderer.setFrameListioner(new NewFrameListioner() {
            @Override
            public void onNewFrame(VideoFrame frame) {

//                VideoFrame.I420Buffer mainBuffer = frame.getBuffer().toI420();
//                // YUV merging
//                int Yb = mainBuffer.getDataY().remaining();
//                int Ub = mainBuffer.getDataU().remaining();
//                int Vb = mainBuffer.getDataV().remaining();
//
//                byte[] data = new byte[Yb + Ub + Vb];
//
//                mainBuffer.getDataY().get(data, 0, Yb);
//                mainBuffer.getDataU().get(data, Yb, Ub);
//                mainBuffer.getDataV().get(data, Yb + Ub, Vb);
//
//
//                ByteArrayOutputStream out = new ByteArrayOutputStream();
//                int[] strides = new int[2];
//                strides[0] = mainBuffer.getStrideY();
//                strides[1] = mainBuffer.getStrideU();
//                strides[2] = mainBuffer.getStrideV();
//
//                YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21, frame.getRotatedWidth(), frame.getRotatedHeight(), strides);
//                yuvImage.compressToJpeg(new Rect(0, 0, frame.getRotatedWidth(), frame.getRotatedHeight()), 100, out);
//                byte[] imageBytes = out.toByteArray();
//                Bitmap image = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
//                //iv.setImageBitmap(image);

                Log.d(TAG, "new Frame by CameraRenderer with Id : ");

                /* o

                Other Solution to Merge All them
                    Image.Plane Y = image.getPlanes()[0];
    Image.Plane U = image.getPlanes()[1];
    Image.Plane V = image.getPlanes()[2];

    int Yb = Y.getBuffer().remaining();
    int Ub = U.getBuffer().remaining();
    int Vb = V.getBuffer().remaining();

    byte[] data = new byte[Yb + Ub + Vb];

    Y.getBuffer().get(data, 0, Yb);
    U.getBuffer().get(data, Yb, Ub);
    V.getBuffer().get(data, Yb+ Ub, Vb);

                 */

//                long startTimei = SystemClock.uptimeMillis();
//                Bitmap tbmp2 = Bitmap.createBitmap(frame.getRotatedWidth(), frame.getRotatedHeight(), Bitmap.Config.ARGB_8888);
//                Mat picyv12 = new Mat(frame.getRotatedHeight() * 3 / 2, frame.getRotatedWidth(), CV_8UC1);  //(im_height*3/2,im_width), should be even no...
//
//                byte[] arr = new byte[frame.getBuffer().getHeight() * fra];
//                frame.getBuffer().toI420().getDataY().get(arr, 0, frame.getBuffer().toI420().getDataY().capacity());
//                picyv12.put(0, 0, frame.getBuffer().toI420().getDataY().capacity()); // buffer - byte array with i420 data
//                cvtColor(picyv12, picyv12, Imgproc.COLOR_YUV2BGR_YV12);
//                long endTimei = SystemClock.uptimeMillis();
//                Log.d("i420_time", Long.toString(endTimei - startTimei) + " ," + frame.getRotatedWidth() + "," + frame.getRotatedHeight());
//                Log.d("picyv12_size", picyv12.size().toString()); // Check size
//                Log.d("picyv12_type", String.valueOf(picyv12.type())); // Check type

//                VideoFrame.I420Buffer yuVBuffer = frame.getBuffer().toI420();
//                // New Impl
//                byte[] nv21;
//                ByteBuffer yBuffer = yuVBuffer.getDataY();
//                ByteBuffer uBuffer = yuVBuffer.getDataU();
//                ByteBuffer vBuffer = yuVBuffer.getDataV();
//
//                int ySize = yBuffer.remaining();
//                int uSize = uBuffer.remaining();
//                int vSize = vBuffer.remaining();
//
//                nv21 = new byte[ySize + uSize + vSize];
//
//                //U and V are swapped
//                yBuffer.get(nv21, 0, ySize);
//                vBuffer.get(nv21, ySize, vSize);
//                uBuffer.get(nv21, ySize + vSize, uSize);
//
//                Mat mRGB = getYUV2Mat(nv21, frame.getRotatedHeight(), frame.getRotatedWidth());
//
//                Bitmap tbmp2 = Bitmap.createBitmap(frame.getRotatedWidth(), frame.getRotatedHeight(), Bitmap.Config.ARGB_8888);
//
//                Utils.matToBitmap(mRGB, tbmp2); // Convert mat to bitmap (height, width) i.e (512,512) - ARGB_888
//                //       SaveBitmap.save(mContext,tbmp2,"Segmented");
//                //  save(tbmp2,"itest"); // Save bitmap

//
//                Bitmap original = tbmp2;
//
//              //  Log.d(TAG, "new Frame by CameraRenderer with Id : ");
            }

            @Override
            public void onNewTexture(SurfaceTexture texture) {
//                Log.d(TAG, "new Texture by CameraRenderer");
//                runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        camTexture.setSurfaceTexture(texture);
//                    }
//                });
//
//                texture.detachFromGLContext();

            }
        });

        pipViewRenderer.setFrameListioner(new NewFrameListioner() {
            @Override
            public void onNewFrame(VideoFrame frame) {
                // Log.d(TAG,"new Frame by pipViewRenderer");
            }

            @Override
            public void onNewTexture(SurfaceTexture texture) {
//                Log.d(TAG, "new Texture by pipViewRenderer");
//                runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        pipTexture.setSurfaceTexture(texture);
//                    }
//                });
//
//                texture.detachFromGLContext();
            }
        });

        // this.getIntent().putExtra(CallActivity.EXTRA_VIDEO_FPS, 24);
        webRTCClient.init(SERVER_URL, streamId, webRTCMode, tokenId, this.getIntent());
        webRTCClient.setDataChannelObserver(this);
    }

//    private void setupPreviewDisplayView() {
//        previewDisplayView.setVisibility(View.GONE);
//        ViewGroup viewGroup = findViewById(R.id.publisher_container);
//        viewGroup.addView(previewDisplayView);
//    }


    public void startStreaming(View v) {

        if (!webRTCClient.isStreaming()) {
            ((Button) v).setText("Stop " + operationName);
            webRTCClient.startStream();
            if (webRTCMode == IWebRTCClient.MODE_JOIN) {
                pipViewRenderer.setZOrderOnTop(true);
            }
        } else {
            ((Button) v).setText("Start " + operationName);
            webRTCClient.stopStream();
            webRTCClient.startStream();
            stoppedStream = true;
        }

    }

    private void attempt2Reconnect() {
        Log.w(getClass().getSimpleName(), "Attempt2Reconnect called");
        if (!webRTCClient.isStreaming()) {
            webRTCClient.startStream();
            if (webRTCMode == IWebRTCClient.MODE_JOIN) {
                pipViewRenderer.setZOrderOnTop(true);
            }
        }
    }

    @Override
    public void onPlayStarted(String streamId) {
        Log.w(getClass().getSimpleName(), "onPlayStarted");
        Toast.makeText(this, "Play started", Toast.LENGTH_LONG).show();
        webRTCClient.switchVideoScaling(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        webRTCClient.getStreamInfoList();
    }

    @Override
    public void onPublishStarted(String streamId) {
        Log.w(getClass().getSimpleName(), "onPublishStarted");
        Toast.makeText(this, "Publish started", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onPublishFinished(String streamId) {
        Log.w(getClass().getSimpleName(), "onPublishFinished");
        Toast.makeText(this, "Publish finished", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onPlayFinished(String streamId) {
        Log.w(getClass().getSimpleName(), "onPlayFinished");
        Toast.makeText(this, "Play finished", Toast.LENGTH_LONG).show();
    }

    @Override
    public void noStreamExistsToPlay(String streamId) {
        Log.w(getClass().getSimpleName(), "noStreamExistsToPlay");
        Toast.makeText(this, "No stream exist to play", Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    public void streamIdInUse(String streamId) {
        Log.w(getClass().getSimpleName(), "streamIdInUse");
        Toast.makeText(this, "Stream id is already in use.", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onError(String description, String streamId) {
        Toast.makeText(this, "Error: " + description, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        webRTCClient.stopStream();
    }

    @Override
    public void onSignalChannelClosed(WebSocket.WebSocketConnectionObserver.WebSocketCloseNotification code, String streamId) {
        Toast.makeText(this, "Signal channel closed with code " + code, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDisconnected(String streamId) {
        Log.w(getClass().getSimpleName(), "disconnected");
        Toast.makeText(this, "Disconnected", Toast.LENGTH_LONG).show();

        startStreamingButton.setText("Start " + operationName);
        // handle reconnection attempt
        if (!stoppedStream) {
            Toast.makeText(this, "Disconnected Attempting to reconnect", Toast.LENGTH_LONG).show();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (!reconnectionHandler.hasCallbacks(reconnectionRunnable)) {
                    reconnectionHandler.postDelayed(reconnectionRunnable, RECONNECTION_PERIOD_MLS);
                }
            } else {
                reconnectionHandler.postDelayed(reconnectionRunnable, RECONNECTION_PERIOD_MLS);
            }
        } else {
            Toast.makeText(this, "Stopped the stream", Toast.LENGTH_LONG).show();
            stoppedStream = false;
        }
    }

    @Override
    public void onIceConnected(String streamId) {
        //it is called when connected to ice
        startStreamingButton.setText("Stop " + operationName);
        // remove scheduled reconnection attempts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (reconnectionHandler.hasCallbacks(reconnectionRunnable)) {
                reconnectionHandler.removeCallbacks(reconnectionRunnable, null);
            }
        } else {
            reconnectionHandler.removeCallbacks(reconnectionRunnable, null);
        }
    }

    @Override
    public void onIceDisconnected(String streamId) {
        //it's called when ice is disconnected
    }

    public void onOffVideo(View view) {
        if (webRTCClient.isVideoOn()) {
            webRTCClient.disableVideo();
        } else {
            webRTCClient.enableVideo();
        }
    }

    public void onOffAudio(View view) {
        if (webRTCClient.isAudioOn()) {
            webRTCClient.disableAudio();
        } else {
            webRTCClient.enableAudio();
        }
    }

    @Override
    public void onTrackList(String[] tracks) {

    }

    @Override
    public void onBitrateMeasurement(String streamId, int targetBitrate, int videoBitrate, int audioBitrate) {
        Log.e(getClass().getSimpleName(), "st:" + streamId + " tb:" + targetBitrate + " vb:" + videoBitrate + " ab:" + audioBitrate);
        if (targetBitrate < (videoBitrate + audioBitrate)) {
            Toast.makeText(this, "low bandwidth", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onStreamInfoList(String streamId, ArrayList<StreamInfo> streamInfoList) {
        String[] stringArray = new String[streamInfoList.size()];
        int i = 0;
        for (StreamInfo si : streamInfoList) {
            stringArray[i++] = si.getHeight() + "";
        }
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, android.R.id.text1, stringArray);
        streamInfoListSpinner.setAdapter(modeAdapter);
    }

    /**
     * This method is used in an experiment. It's not for production
     *
     * @param streamId
     */
    public void calculateAbsoluteLatency(String streamId) {
        String url = REST_URL + "/broadcasts/" + streamId + "/rtmp-to-webrtc-stats";

        RequestQueue queue = Volley.newRequestQueue(this);


        StringRequest stringRequest = new StringRequest(Request.Method.GET, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        try {
                            Log.i("MainActivity", "recevied response " + response);
                            JSONObject jsonObject = new JSONObject(response);
                            long absoluteStartTimeMs = jsonObject.getLong("absoluteTimeMs");
                            //this is the frame id in sending the rtp packet. Actually it's rtp timestamp
                            long frameId = jsonObject.getLong("frameId");
                            long relativeCaptureTimeMs = jsonObject.getLong("captureTimeMs");
                            long captureTimeMs = frameId / 90;
                            Map<Long, Long> captureTimeMsList = WebRTCClient.getCaptureTimeMsMapList();

                            long absoluteDecodeTimeMs = 0;
                            if (captureTimeMsList.containsKey(captureTimeMs)) {
                                absoluteDecodeTimeMs = captureTimeMsList.get(captureTimeMs);
                            }

                            long absoluteLatency = absoluteDecodeTimeMs - relativeCaptureTimeMs - absoluteStartTimeMs;
                            Log.i("MainActivity", "recevied absolute start time: " + absoluteStartTimeMs
                                    + " frameId: " + frameId + " relativeLatencyMs : " + relativeCaptureTimeMs
                                    + " absoluteDecodeTimeMs: " + absoluteDecodeTimeMs
                                    + " absoluteLatency: " + absoluteLatency);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }


                    }
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.e("MainActivity", "That didn't work!");

            }
        });

        // Add the request to the RequestQueue.
        queue.add(stringRequest);

    }

    @Override
    public void onBufferedAmountChange(long previousAmount, String dataChannelLabel) {
        Log.d(MainActivity.class.getName(), "Data channel buffered amount changed: ");
    }

    @Override
    public void onStateChange(DataChannel.State state, String dataChannelLabel) {
        Log.d(MainActivity.class.getName(), "Data channel state changed: ");
    }

    @Override
    public void onMessage(DataChannel.Buffer buffer, String dataChannelLabel) {
        ByteBuffer data = buffer.data;
        String messageText = new String(data.array(), StandardCharsets.UTF_8);
        Toast.makeText(this, "New Message: " + messageText, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onMessageSent(DataChannel.Buffer buffer, boolean successful) {
        if (successful) {
            ByteBuffer data = buffer.data;
            final byte[] bytes = new byte[data.capacity()];
            data.get(bytes);
            String messageText = new String(bytes, StandardCharsets.UTF_8);

            Toast.makeText(this, "Message is sent", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Could not send the text message", Toast.LENGTH_LONG).show();
        }
    }

    public void sendTextMessage(String messageToSend) {
        final ByteBuffer buffer = ByteBuffer.wrap(messageToSend.getBytes(StandardCharsets.UTF_8));
        DataChannel.Buffer buf = new DataChannel.Buffer(buffer, false);
        webRTCClient.sendMessageViaDataChannel(buf);
    }

    public void showSendDataChannelMessageDialog(View view) {
        if (webRTCClient != null && webRTCClient.isDataChannelEnabled()) {
            // create an alert builder
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Send Message via Data Channel");
            // set the custom layout
            final View customLayout = getLayoutInflater().inflate(R.layout.send_message_data_channel, null);
            builder.setView(customLayout);
            // add a button
            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // send data from the AlertDialog to the Activity
                    EditText editText = customLayout.findViewById(R.id.message_text_input);
                    sendTextMessage(editText.getText().toString());
                    // sendDialogDataToActivity(editText.getText().toString());
                }
            });
            builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {

                }
            });
            // create and show the alert dialog
            AlertDialog dialog = builder.create();
            dialog.show();
        } else {
            Toast.makeText(this, R.string.data_channel_not_available, Toast.LENGTH_LONG).show();
        }
    }

    // For YUV conversion
    public Mat getYUV2Mat(byte[] data, int height, int width) {
        Mat mYuv = new Mat(height + height / 2, width, CV_8UC1);
        mYuv.put(0, 0, data);
        Mat mRGB = new Mat();
        cvtColor(mYuv, mRGB, Imgproc.COLOR_YUV2RGB_NV21, 3);
        return mRGB;
    }

    private void mediaPlay() {
//        try {
//            surfaceTexture = new MySurfaceTexture(42);
//            player = new NewDefaultPlayer();
//            player.setSurface(new Surface(surfaceTexture));
//            player.setDataSource("https://multiplatform-f.akamaihd.net/i/multi/will/bunny/big_buck_bunny_,640x360_400,640x360_700,640x360_1000,950x540_1500,.f4v.csmil/master.m3u8");
//            player.setLooping(true);
//            //player.setBufferEventInfoListner(bufferEventInfoListner);
//            player.prepare();
//            player.start();
//        } catch (Exception e) {
//            Log.d(TAG, e.toString());
//            e.printStackTrace();
//        }
    }

}
