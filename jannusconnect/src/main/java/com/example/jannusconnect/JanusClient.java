package com.example.jannusconnect;



import android.content.Context;
import android.util.Log;



import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 参考1：https://blog.csdn.net/Java_lilin/article/details/104007291
 * 参考2：https://github.com/benwtrent/janus-gateway-android
 * 参考3：https://zhuanlan.zhihu.com/p/149324861?utm_source=wechat_session
 */
public class JanusClient implements WebSocketChannel.WebSocketCallback {
    private static final String TAG = "JanusClient";
    private ConcurrentHashMap<BigInteger, PluginHandle> attachedPlugins = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Transaction> transactions = new ConcurrentHashMap<>();
    private BigInteger sessionId = null;
    private JanusCallback janusCallback;

    private volatile boolean isKeepAliveRunning;
    private Thread keepAliveThread;

    private String janusUrl ="";
    private WebSocketChannel webSocketChannel;

    PeerConnection peerConnection;

    BigInteger roomHandlerId;

    private DataChannelMessageListener listener;
    private PeerConnectionFactory peerConnectionFactory;

    DataChannel.Init dataChannelInit;
    DataChannel dataChannel;

    private String UserName = "TSE6UXU";
    private int Room = 1234;

    private boolean isJanusConnected = false ;

    private boolean isJanusConnecting = false ;

    private JanusClientCallback mCallback;
    private boolean mIsDestroying = false;

    public interface DataChannelMessageListener {
        void onDataReceived(String data);
    }


    public void setListener(DataChannelMessageListener listener) {
        this.listener = listener;
    }


    private static JanusClient instance;
    private Context appContext;

    private JanusClient() {
        // 私有構造函數，確保只能透過 getInstance 方法獲取單一實例
    }

    public static synchronized JanusClient getInstance() {
        if (instance == null) {
            instance = new JanusClient();
        }
        return instance;
    }


    public void initialize(String janusUrl, int eventId, String chatroomId, Context context, JanusClientCallback callback) {
        Log.e(TAG, "initialize : janusUrl : "+janusUrl);
        this.Room = eventId;
        this.UserName = chatroomId;
        this.janusUrl = janusUrl;
        this.appContext = context;
        this.mCallback = callback;
        connect();
    }

    public void setJanusCallback(JanusCallback janusCallback) {
        this.janusCallback = janusCallback;
    }

    public void connect() {
        Log.e(TAG, "connect : janusUrl : "+janusUrl);
        if (!isJanusConnected&&!isJanusConnecting&&janusUrl!=""){
            isJanusConnecting = true;
            initWebSocketChannel();
            webSocketChannel.connect(janusUrl);
        }
    }

    public void disConnect() {
        stopKeepAliveTimer();
        if (webSocketChannel != null) {
            webSocketChannel.close();
            webSocketChannel = null;
        }
        isJanusConnected = false;
        isJanusConnecting = false;
    }

    private void initWebSocketChannel(){
        if (webSocketChannel == null){
            webSocketChannel = new WebSocketChannel();
            webSocketChannel.setWebSocketCallback(this);
            JanusCallback janusCallback = new JanusCallback() {
                @Override
                public void onCreateSession(BigInteger onAttached) {
                    //TODO: step2.連接textroom plugin
                    Log.e(TAG, "onCreateSession: sessionId=" + onAttached);
                    attachPlugin("janus.plugin.textroom");
                }

                @Override
                public void onAttached(BigInteger handleId) {
                    Log.e(TAG, "onAttached: handlerId=" + handleId);
                    roomHandlerId = handleId;
                    //TODO: step3.啟動textroom plugin
                    Log.e(TAG, "setupTextRoom");
                    setupTextRoom(roomHandlerId);
                }

                @Override
                public void onSubscribeAttached(BigInteger subscribeHandleId, BigInteger feedId) {
                    Log.e(TAG, "onSubscribeAttached: subscribeHandleId = "+subscribeHandleId);
                }

                @Override
                public void onDetached(BigInteger handleId) {
                    Log.e(TAG, "onDetached: handleId= "+handleId);
                }

                @Override
                public void onHangup(BigInteger handleId) {
                    Log.e(TAG, "onHangup: handleId = "+handleId);
                }

                @Override
                public void onMessage(BigInteger sender, BigInteger handleId, JSONObject msg, JSONObject jsep) {
                    String TAG = "JanusClient.onMessage";
                    Log.e(TAG, "onMessage: sender= "+sender+"  , handleId = "+handleId);
                    if (msg != null){
                        Log.e(TAG, "onMessage: msg= "+msg.toString());
                    }

                    //TODO: step4. 建立peerConnecttion

                    peerConnection = createPeerConnection();

                    if (jsep != null) {
                        Log.e(TAG, jsep.toString());
                        try {
                            String sdp = jsep.getString("sdp");
                            String type = jsep.getString("type");

                            Log.e(TAG, "onMessage: peerConnection sdp type= "+type);

                            //TODO: step5.連接offer 的 setRemoteDescription(with jsep)
                            if (type.equals("offer")) {
                                peerConnection.setRemoteDescription(new SdpObserver() {
                                    @Override
                                    public void onCreateSuccess(SessionDescription sdp) {
                                        Log.d(TAG, "setRemoteDescription onCreateSuccess");
                                    }

                                    @Override
                                    public void onSetSuccess() {
                                        Log.d(TAG, "setRemoteDescription onSetSuccess");
                                        //TODO: step6.連接offer 成功後 回傳 answer 給server
                                        createAnswer(peerConnection, new CreateAnswerCallback() {
                                            @Override
                                            public void onSetAnswerSuccess(SessionDescription sdp) {
                                                Log.d(TAG, "onSetAnswerSuccess ");
                                                //TODO: step8.回傳localDescription的answer
                                                answerTextRoom(roomHandlerId, sdp);

                                                //TODO: step9.建立 dataChannel
                                                createDataChannel();
                                            }

                                            @Override
                                            public void onSetAnswerFailed(String error) {

                                            }
                                        });


                                    }

                                    @Override
                                    public void onCreateFailure(String error) {
                                        Log.d(TAG, "setRemoteDescription onCreateFailure " + error);
                                    }

                                    @Override
                                    public void onSetFailure(String error) {
                                        Log.d(TAG, "setRemoteDescription onSetFailure " + error);
                                    }
                                }, new SessionDescription(SessionDescription.Type.OFFER, sdp));
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }

                }

                @Override
                public void onIceCandidate(BigInteger handleId, JSONObject candidate) {
                    Log.e(TAG, "onIceCandidate");
                }

                @Override
                public void onDestroySession(BigInteger sessionId) {
                    Log.e(TAG, "onDestroySession , sessionId : "+sessionId);
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "onError:" + error);
                }
            };
            setJanusCallback(janusCallback);
        }
    }
    private void createSession() {
        String tid = randomString(12);
        transactions.put(tid, new Transaction(tid) {
            @Override
            public void onSuccess(JSONObject msg) throws Exception {
                JSONObject data = msg.getJSONObject("data");
                sessionId = new BigInteger(data.getString("id"));
                Log.e(TAG,"createSession , onSuccess: sessionId = "+sessionId);
                startKeepAliveTimer();
                if (janusCallback != null) {
                    janusCallback.onCreateSession(sessionId);
                }
            }
        });
        try {
            JSONObject obj = new JSONObject();
            obj.put("janus", "create");
            obj.put("transaction", tid);
            webSocketChannel.sendMessage(obj.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void destroySession() {
        mIsDestroying = true;
        String tid = randomString(12);
        transactions.put(tid, new Transaction(tid) {
            @Override
            public void onSuccess(JSONObject msg) throws Exception {
                stopKeepAliveTimer();
                if (janusCallback != null) {
                    janusCallback.onDestroySession(sessionId);
                }
            }
        });
        try {
            JSONObject obj = new JSONObject();
            obj.put("janus", "destroy");
            obj.put("transaction", tid);
            obj.put("session_id", sessionId);
            webSocketChannel.sendMessage(obj.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void attachPlugin(String pluginName) {
        String tid = randomString(12);
        transactions.put(tid, new Transaction(tid) {
            @Override
            public void onSuccess(JSONObject msg) throws Exception {
                JSONObject data = msg.getJSONObject("data");
                BigInteger handleId = new BigInteger(data.getString("id"));
                if (janusCallback != null) {
                    janusCallback.onAttached(handleId);
                }
                PluginHandle handle = new PluginHandle(handleId);
                attachedPlugins.put(handleId, handle);
            }
        });

        try {
            JSONObject obj = new JSONObject();
            obj.put("janus", "attach");
            obj.put("transaction", tid);
            obj.put("plugin", pluginName);
            obj.put("session_id", sessionId);
            webSocketChannel.sendMessage(obj.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * attach 到发布者的 handler 上，准备接收视频流
     * 每个发布者都要 attach 一遍，然后协商 sdp, SFU
     *
     * @param feedId
     */
    public void subscribeAttach(BigInteger feedId) {
        String tid = randomString(12);
        transactions.put(tid, new Transaction(tid, feedId) {
            @Override
            public void onSuccess(JSONObject msg, BigInteger feedId) throws Exception {
                JSONObject data = msg.getJSONObject("data");
                BigInteger handleId = new BigInteger(data.getString("id"));
                if (janusCallback != null) {
                    janusCallback.onSubscribeAttached(handleId, feedId);
                }
                PluginHandle handle = new PluginHandle(handleId);
                attachedPlugins.put(handleId, handle);
            }
        });

        try {
            JSONObject obj = new JSONObject();
            obj.put("janus", "attach");
            obj.put("transaction", tid);
            obj.put("plugin", "janus.plugin.videoroom");
            obj.put("session_id", sessionId);
            webSocketChannel.sendMessage(obj.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void createOffer(BigInteger handleId, SessionDescription sdp) {
        JSONObject message = new JSONObject();
        try {
            JSONObject publish = new JSONObject();
            publish.putOpt("audio", true);
            publish.putOpt("video", true);

            JSONObject jsep = new JSONObject();
            jsep.putOpt("type", sdp.type);
            jsep.putOpt("sdp", sdp.description);

            message.putOpt("janus", "message");
            message.putOpt("body", publish);
            message.putOpt("jsep", jsep);
            message.putOpt("transaction", randomString(12));
            message.putOpt("session_id", sessionId);
            message.putOpt("handle_id", handleId);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        webSocketChannel.sendMessage(message.toString());
    }

    /**
     * 开始订阅
     *
     * @param subscriptionHandleId
     * @param sdp
     */
    public void subscriptionStart(BigInteger subscriptionHandleId, int roomId, SessionDescription sdp) {
        JSONObject message = new JSONObject();
        try {
            JSONObject body = new JSONObject();
            body.putOpt("request", "start");
            body.putOpt("room", roomId);

            message.putOpt("janus", "message");
            message.putOpt("body", body);
            message.putOpt("transaction", randomString(12));
            message.putOpt("session_id", sessionId);
            message.putOpt("handle_id", subscriptionHandleId);

            if (sdp != null) {
                JSONObject jsep = new JSONObject();
                jsep.putOpt("type", sdp.type);
                jsep.putOpt("sdp", sdp.description);
                message.putOpt("jsep", jsep);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        webSocketChannel.sendMessage(message.toString());
    }

    public void publish(BigInteger handleId, SessionDescription sdp) {
        JSONObject message = new JSONObject();
        try {
            JSONObject publish = new JSONObject();
            publish.putOpt("request", "publish");
            publish.putOpt("audio", true);
            publish.putOpt("video", true);

            JSONObject jsep = new JSONObject();
            jsep.putOpt("type", sdp.type);
            jsep.putOpt("sdp", sdp.description);

            message.putOpt("janus", "message");
            message.putOpt("body", publish);
            message.putOpt("jsep", jsep);
            message.putOpt("transaction", randomString(12));
            message.putOpt("session_id", sessionId);
            message.putOpt("handle_id", handleId);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        webSocketChannel.sendMessage(message.toString());
    }

    /**
     * 订阅
     *
     * @param roomId 房间ID
     * @param feedId 要订阅的ID
     */
    public void subscribe(BigInteger subscriptionHandleId, int roomId, BigInteger feedId) {
        JSONObject message = new JSONObject();
        JSONObject body = new JSONObject();
        try {
            body.putOpt("ptype", "subscriber");
            body.putOpt("request", "join");
            body.putOpt("room", roomId);
            body.putOpt("feed", feedId);

            message.put("body", body);
            message.putOpt("janus", "message");
            message.putOpt("transaction", randomString(12));
            message.putOpt("session_id", sessionId);
            message.putOpt("handle_id", subscriptionHandleId);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        webSocketChannel.sendMessage(message.toString());
    }

    public void trickleCandidate(BigInteger handleId, IceCandidate iceCandidate) {
        JSONObject candidate = new JSONObject();
        JSONObject message = new JSONObject();
        try {
            candidate.putOpt("candidate", iceCandidate.sdp);
            candidate.putOpt("sdpMid", iceCandidate.sdpMid);
            candidate.putOpt("sdpMLineIndex", iceCandidate.sdpMLineIndex);

            message.putOpt("janus", "trickle");
            message.putOpt("candidate", candidate);
            message.putOpt("transaction", randomString(12));
            message.putOpt("session_id", sessionId);
            message.putOpt("handle_id", handleId);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        webSocketChannel.sendMessage(message.toString());
    }

    public void trickleCandidateComplete(BigInteger handleId) {
        JSONObject candidate = new JSONObject();
        JSONObject message = new JSONObject();
        try {
            candidate.putOpt("completed", true);

            message.putOpt("janus", "trickle");
            message.putOpt("candidate", candidate);
            message.putOpt("transaction", randomString(12));
            message.putOpt("session_id", sessionId);
            message.putOpt("handle_id", handleId);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        webSocketChannel.sendMessage(message.toString());
    }

    public void joinRoom(BigInteger handleId, int roomId, String displayName) {
        JSONObject message = new JSONObject();
        JSONObject body = new JSONObject();
        try {

            body.putOpt("request", "join");
            body.putOpt("room", roomId);
            body.putOpt("ptype", "publisher");
            body.putOpt("display", displayName);
            message.put("body", body);

            message.putOpt("janus", "message");
            message.putOpt("transaction", randomString(12));
            message.putOpt("session_id", sessionId);
            message.putOpt("handle_id", handleId);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        webSocketChannel.sendMessage(message.toString());
    }

    public void setupTextRoom(BigInteger handleId){
        JSONObject message = new JSONObject();
        JSONObject body = new JSONObject();
        try {


            body.putOpt("request", "setup");

            message.put("body", body);
            message.putOpt("janus", "message");
            message.putOpt("transaction", randomString(12));
            message.putOpt("session_id", sessionId);
            message.putOpt("handle_id", handleId);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        webSocketChannel.sendMessage(message.toString());
    }

    public void answerTextRoom(BigInteger handleId,SessionDescription sdp){
        JSONObject message = new JSONObject();
        JSONObject body = new JSONObject();
        try {
            JSONObject jsep = new JSONObject();
            jsep.putOpt("type", sdp.type);
            jsep.putOpt("sdp", sdp.description);

            body.putOpt("request", "ack");


            message.put("body", body);
            message.putOpt("janus", "message");
            message.putOpt("jsep", jsep);
            message.putOpt("transaction", randomString(12));
            message.putOpt("session_id", sessionId);
            message.putOpt("handle_id", handleId);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        webSocketChannel.sendMessage(message.toString());
    }

    @Override
    public void onOpen() {
        Log.d(TAG, "WebSocket onOpen ");
        createSession();
    }

    @Override
    public void onMessage(String message) {
        Log.d(TAG, "收到消息》》》" + message);
        try {
            JSONObject obj = new JSONObject(message);
            JanusMessageType type = JanusMessageType.fromString(obj.getString("janus"));
            String transaction = null;
            BigInteger sender = null;
            if (obj.has("transaction")) {
                transaction = obj.getString("transaction");
            }
            if (obj.has("sender")) {
                sender = new BigInteger(obj.getString("sender"));
            }
            PluginHandle handle = null;
            if (sender != null) {
                handle = attachedPlugins.get(sender);
            }
            switch (type) {
                case keepalive:
                    break;
                case ack:
                    break;
                case success:
                    if (transaction != null) {
                        Transaction cb = transactions.get(transaction);
                        if (cb != null) {
                            try {
                                if (cb.getFeedId() != null) {
                                    cb.onSuccess(obj, cb.getFeedId());
                                } else {
                                    cb.onSuccess(obj);
                                }
                                transactions.remove(transaction);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                    break;
                case error: {
                    if (transaction != null) {
                        Transaction cb = transactions.get(transaction);
                        if (cb != null) {
                            cb.onError();
                            transactions.remove(transaction);
                        }
                    }
                    break;
                }
                case hangup: {
                    break;
                }
                case detached: {
                    if (handle != null) {
                        if (janusCallback != null) {
                            janusCallback.onDetached(handle.getHandleId());
                        }
                    }
                    break;
                }
                case event: {
                    if (handle != null) {
                        JSONObject plugin_data = null;
                        if (obj.has("plugindata")) {
                            plugin_data = obj.getJSONObject("plugindata");
                        }
                        if (plugin_data != null) {
                            JSONObject data = null;
                            JSONObject jsep = null;
                            if (plugin_data.has("data")) {
                                data = plugin_data.getJSONObject("data");
                            }
                            if (obj.has("jsep")) {
                                jsep = obj.getJSONObject("jsep");
                            }
                            if (janusCallback != null) {
                                janusCallback.onMessage(sender, handle.getHandleId(), data, jsep);
                            }
                        }
                    }
                }
                case trickle:
                    if (handle != null) {
                        if (obj.has("candidate")) {
                            JSONObject candidate = obj.getJSONObject("candidate");
                            if (janusCallback != null) {
                                janusCallback.onIceCandidate(handle.getHandleId(), candidate);
                            }
                        }
                    }
                    break;
                case destroy:
                    if (janusCallback != null) {
                        janusCallback.onDestroySession(sessionId);
                    }
                    break;
            }
        } catch (JSONException ex) {
            if (janusCallback != null) {
                janusCallback.onError(ex.getMessage());
            }
        }
    }

    @Override
    public void onClosed() {
        stopKeepAliveTimer();
    }

    private void startKeepAliveTimer() {
        isKeepAliveRunning = true;
        keepAliveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (isKeepAliveRunning && !Thread.interrupted()) {
                    try {
                        Thread.sleep(25000);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                    if (webSocketChannel != null && webSocketChannel.isConnected()) {
                        JSONObject obj = new JSONObject();
                        try {
                            obj.put("janus", "keepalive");
                            obj.put("session_id", sessionId);
                            obj.put("transaction", randomString(12));
                            webSocketChannel.sendMessage(obj.toString());
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    } else {
                        Log.e(TAG, "keepAlive failed websocket is null or not connected");
                    }
                }
                Log.d(TAG, "keepAlive thread stopped");
            }
        }, "KeepAlive");
        keepAliveThread.start();
    }

    private void stopKeepAliveTimer() {
        isKeepAliveRunning = false;
        if (keepAliveThread != null) {
            keepAliveThread.interrupt();
        }
    }



    public String randomString(int length) {
        String str = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        Random random = new Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(str.charAt(random.nextInt(str.length())));
        }
        return sb.toString();
    }

    public interface JanusCallback {
        /**
         * 代表連接成功後 接下來該連接套件了
        **/
        void onCreateSession(BigInteger sessionId);
        /**
         * 代表觸發套件成功 接下來該送指令
         **/
        void onAttached(BigInteger handleId);

        /**
         * 订阅回调
         *
         * @param subscribeHandleId 订阅HandlerId
         * @param feedId            订阅 feedId
         */
        void onSubscribeAttached(BigInteger subscribeHandleId, BigInteger feedId);

        void onDetached(BigInteger handleId);

        void onHangup(BigInteger handleId);

        void onMessage(BigInteger sender, BigInteger handleId, JSONObject msg, JSONObject jsep);

        void onIceCandidate(BigInteger handleId, JSONObject candidate);

        void onDestroySession(BigInteger sessionId);

        void onError(String error);
    }

    private void createAnswer(PeerConnection peerConnection, CreateAnswerCallback callback) {
        final String TAG = "createAnswer";
        MediaConstraints mediaConstraints = new MediaConstraints();
        peerConnection.createAnswer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                Log.d(TAG, "createAnswer ok");
                Log.d(TAG, "setLocalDescription init");
                //TODO: step7.answer 完成後 建立 localDescription
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {
                    }

                    @Override
                    public void onSetSuccess() {
                        // send answer sdp
                        Log.d(TAG, "createAnswer setLocalDescription onSetSuccess");
                        if (callback != null) {
                            callback.onSetAnswerSuccess(sdp);
                        }
                    }

                    @Override
                    public void onCreateFailure(String s) {
                        Log.d(TAG, "createAnswer setLocalDescription onCreateFailure " + s);
                        if (callback != null) {
                            callback.onSetAnswerFailed(s);
                        }
                    }

                    @Override
                    public void onSetFailure(String s) {
                        Log.d(TAG, "createAnswer setLocalDescription onSetFailure " + s);
                        if (callback != null) {
                            callback.onSetAnswerFailed(s);
                        }
                    }
                }, sdp);
            }

            @Override
            public void onSetSuccess() {
                Log.d(TAG, "createAnswer onSetSuccess");
            }

            @Override
            public void onCreateFailure(String s) {
                Log.d(TAG, "createAnswer onCreateFailure " + s);
            }

            @Override
            public void onSetFailure(String s) {
                Log.d(TAG, "createAnswer onSetFailure " + s);
            }
        }, mediaConstraints);
    }

    private PeerConnection createPeerConnection() {
        PeerConnectionFactory.InitializationOptions initializationOptions = PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();

        peerConnectionFactory = PeerConnectionFactory.builder().setOptions(options).createPeerConnectionFactory();
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);

        return peerConnectionFactory.createPeerConnection(rtcConfig, peerConnectionObserver);

    }

    private PeerConnection.Observer peerConnectionObserver = new PeerConnection.Observer() {
        final String TAG = "PeerConnection.Observer";

        @Override
        public void onSignalingChange(PeerConnection.SignalingState newState) {
            Log.d(TAG, "onSignalingChange " + newState);
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
            Log.e(TAG, "onIceConnectionChange " + newState);
            if (newState == PeerConnection.IceConnectionState.DISCONNECTED){
                if (mCallback != null){
                    mCallback.onConnectionStateChanged(ConnectionState.DISCONNECTED);
                }
                isJanusConnected = false;
                isJanusConnecting = false;
                if (!mIsDestroying){
                    connect();
                }
            }else if (newState == PeerConnection.IceConnectionState.CONNECTED){
                isJanusConnected = true;
                isJanusConnecting = false;
                if (mCallback != null){
                    mCallback.onConnectionStateChanged(ConnectionState.CONNECTED);
                }
            } else if (newState == PeerConnection.IceConnectionState.CLOSED
                      || newState == PeerConnection.IceConnectionState.FAILED) {
                if (mCallback != null) {
                    mCallback.onConnectionStateChanged(ConnectionState.DISCONNECTED);
                }
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
            Log.d(TAG, "onIceConnectionReceivingChange " + receiving);
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
            Log.d(TAG, "onIceGatheringChange " + newState);
            if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                trickleCandidateComplete(roomHandlerId);
            }
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            Log.d(TAG, "onIceCandidate");
            trickleCandidate(roomHandlerId, candidate);
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {
            Log.d(TAG, "onIceCandidatesRemoved");
            peerConnection.removeIceCandidates(candidates);
        }

        @Override
        public void onAddStream(MediaStream stream) {
            Log.d(TAG, "onAddStream");
//            stream.videoTracks.get(0).addSink(surfaceViewRendererRemote);
        }

        @Override
        public void onRemoveStream(MediaStream stream) {
            Log.d(TAG, "onRemoveStream");
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {

            Log.d(TAG, "dataChannel" + dataChannel.id());

        }

        @Override
        public void onRenegotiationNeeded() {
            Log.d(TAG, "roomHandlerId:" + roomHandlerId);
            Log.d(TAG, "dataChannel:onRenegotiationNeeded");
        }

        @Override
        public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
            Log.d(TAG, "onAddTrack ");
        }
    };

    private void createDataChannel() {
        dataChannelInit = new DataChannel.Init();
        dataChannelInit.ordered = true; // 消息的传递是否有序 true代表有序
        dataChannelInit.negotiated = false; // 协商方式
        dataChannelInit.id = Room; // 通道ID
        dataChannelInit.protocol = "janus-protocol";

        Log.e("createDataChannel", "createDataChannel init");
        dataChannel = peerConnection.createDataChannel("JanusDataChannel", dataChannelInit);
        Log.e("createDataChannel", "registerObserver");
        //TODO: step10. 監聽DataChannel
        dataChannel.registerObserver(dataChannelObserver);


    }

    private final DataChannel.Observer dataChannelObserver = new DataChannel.Observer() {
        final String TAG = "DataChannel.Observer";

        @Override
        public void onBufferedAmountChange(long l) {
            Log.e(TAG, "initDataChannel----->onBufferedAmountChange--->" + l);
        }

        @Override
        public void onStateChange() {
            Log.e(TAG, "initDataChannel----->onStateChange--->" + dataChannel.state());
            switch (dataChannel.state()) {
                case CONNECTING:
                    if (mCallback != null){
                        mCallback.onConnectionStateChanged(ConnectionState.CONNECTING);
                    }
                    break;
                case OPEN: {
                    //TODO: step11. 加入聊天室
                    sendJoin();
                }
                break;
                case CLOSING:
                    if (mCallback != null){
                        mCallback.onConnectionStateChanged(ConnectionState.CLOSING);
                    }
                    break;
                case CLOSED:
                    if (mCallback != null){
                        mCallback.onConnectionStateChanged(ConnectionState.CLOSED);
                    }
                    break;
            }
        }

        @Override
        public void onMessage(DataChannel.Buffer buffer) {
            //TODO: 接收DataChannel訊息的
            Log.e(TAG, "DataChannel----->onMessage--->");
            try {
                ByteBuffer data = buffer.data;
                byte[] bytes = new byte[data.capacity()];
                Log.e(TAG, "initDataChannel----->onMessage--->" + bytes.length);
                data.get(bytes);
                //檔案的處理可參考 https://www.cnblogs.com/tony-yang-flutter/p/15140796.html)
                if (!buffer.binary) {//不是二进制数据
                    //此处接收的是非二进制数据
                    String msg = new String(bytes);
                    Log.e(TAG, "initDataChannel----->onMessage--->" + msg);
                    if (mCallback != null){
                        mCallback.onMessageReceived(msg);
                    }
                    //yilia todo: 確認哪裡呼叫的listener,好像沒用到
                    if (listener != null){
                        listener.onDataReceived(msg);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };


    public void sendJoin() {
        Log.e("dataChannel", "sendJOIN");
        try {
            JSONObject register = new JSONObject();
            register.put("textroom", "join");
            register.put("transaction", randomString(12));
            register.put("room", Room); //eventID
            register.put("username", UserName);  //ChatroomID
            register.put("display", "user");
            sendMsg(register.toString());
        } catch (JSONException e) {
        }
    }

    public void sendLeave() {
        try {
            JSONObject register = new JSONObject();
            register.put("textroom", "leave");
            register.put("transaction", randomString(12));
            register.put("room", Room);
            sendMsg(register.toString());
        } catch (JSONException e) {
        }
    }

    public boolean sendMsg(String message) {
        if (dataChannel != null) {
            if (message != null) {
                Log.e("sendMsg", "sendMsg:" + message);
                byte[] msg = message.getBytes();
                DataChannel.Buffer buffer = new DataChannel.Buffer(ByteBuffer.wrap(msg), false);
                return dataChannel.send(buffer);
            }
        }
        return false;
    }

/*
    public void sendTextMessage(String text) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.UK);
        String formattedDate = sdf.format(new Date());
        Log.e(TAG,"sendTextMessage : text = "+text);
        Log.e(TAG,"sendTextMessage : formattedDate = "+formattedDate);
        if (dataChannel != null) {
            try {
                JSONObject message = new JSONObject();
                JSONObject request = new JSONObject();
                request.put("chatroomId", UserName);
                request.put("msgType", 1);
                request.put("sender", 1);
                request.put("msgContent", text);
                request.put("time", System.currentTimeMillis());
                request.put("type", 2);
                request.put("format", new JSONObject());

                JSONObject config = new JSONObject();
                config.put("id", randomString(12));
                config.put("isReply", false);
                config.put("replyObj", new JSONObject());
                config.put("CurrentDate", formattedDate);
                config.put("isExpire", false);
                config.put("isPlay", false);
                config.put("isRead", false);
                config.put("msgFunctionStatus", false);
                config.put("msgMoreStatus", false);
                config.put("recallPopUp", false);
                config.put("recallStatus", false);
                config.put("userName", UserName);

                request.put("config", config);

                JSONObject textMsg = new JSONObject();
                textMsg.put("janusMsg", request);
                textMsg.put("from", UserName);

                JSONArray tos = new JSONArray();
                tos.put("qI7atzk");
                tos.put("TSE6UXU");

                message.put("transaction", randomString(12));
                message.put("textroom", "message");
                message.put("room", Room);
                message.put("tos", tos);
                message.put("text", textMsg.toString());
                message.put("username", randomString(12));

                Log.e("sendTextMessage", message.toString());

                sendMsg(message.toString());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
*/
    public boolean sendJanusMessage(String transaction, String content, String mChatroomId, String mParticipantId){
        JSONObject message = new JSONObject();
        try {
            message.put("transaction", transaction);
            message.put("textroom", "message");
            message.put("room", Room);

            JSONArray tos = new JSONArray();
            tos.put(mParticipantId);   //對方的ChatroomID
            tos.put(mChatroomId);   //自己的ChatroomID

            message.put("tos", tos);
            message.put("text", content);
            message.put("username", randomString(12));

            Log.e("sendTextMessage"," message : "+ message.toString());
            return sendMsg(message.toString());
        } catch (JSONException e) {
            Log.e("sendTextMessage e :", e.toString());
            return false;
        }
    }


    public interface CreateAnswerCallback {
        void onSetAnswerSuccess(SessionDescription sdp);

        void onSetAnswerFailed(String error);
    }

    public interface JanusClientCallback{
        void onMessageReceived(String message);
        void onConnectionStateChanged(ConnectionState state);
    }

    public enum ConnectionState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        CLOSING,
        CLOSED
    }
}
