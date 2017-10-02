/*
 * Copyright 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.weatherstation;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;

import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.pubsub.Pubsub;
import com.google.api.services.pubsub.PubsubScopes;
import com.google.api.services.pubsub.model.PublishRequest;
import com.google.api.services.pubsub.model.PubsubMessage;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

public class MqttPublisher {
    private static final String TAG = MqttPublisher.class.getSimpleName();

    private final Context mContext;
    private final String mAppname;
    private final String mTopic;

    private Pubsub mPubsub;
    private HttpTransport mHttpTransport;

    private Handler mHandler;
    private HandlerThread mHandlerThread;

    private float mLastTemperature = Float.NaN;
    private float mLastPressure = Float.NaN;

    //thingSpeak supports publishing every 15 seconds
    private static final long PUBLISH_INTERVAL_MS = 15000;

    private static final String MQTT_BROKER_URI = "tcp://mqtt.thingspeak.com:1883";
    private final String clientId = "WeatherStation";
    private MqttAndroidClient mqttAndroidClient;

    public MqttPublisher(Context context, String appname, String topic) throws IOException {
        mContext = context;
        mAppname = appname;
        mTopic = "channels/339843/publish/JHHJ8PHE20Y99H8H";

        //create a new thread and related Looper
        mHandlerThread = new HandlerThread("MQTTPublisherThread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mqttAndroidClient = new MqttAndroidClient(context, MQTT_BROKER_URI, clientId);
        mqttAndroidClient.setCallback(new MqttCallbackExtended() {

            @Override
            public void connectComplete(boolean reconnect, String serverURI) {

                if (reconnect) {
                    addToHistory("Reconnected to : " + serverURI);
                    // Because Clean Session is true, we need to re-subscribe
                    subscribeToTopic();
                } else {
                    addToHistory("Connected to: " + serverURI);
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                addToHistory("The Connection was lost.");
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                addToHistory("Incoming message: " + new String(message.getPayload()));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {

            }
        });

        /*InputStream jsonCredentials = mContext.getResources().openRawResource(credentialResourceId);
        final GoogleCredential credentials;
        try {
            credentials = GoogleCredential.fromStream(jsonCredentials).createScoped(
                    Collections.singleton(PubsubScopes.PUBSUB));
        } finally {
            try {
                jsonCredentials.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing input stream", e);
            }
        }
        mHandler.post(new Runnable() {

            @Override
            public void run() {
                mHttpTransport = AndroidHttp.newCompatibleTransport();
                JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
                mPubsub = new Pubsub.Builder(mHttpTransport, jsonFactory, credentials).setApplicationName(mAppname).build();
            }
        });*/


    }

    public void start() {
        mHandler.post(mPublishRunnable);
    }

    public void stop() {
        mHandler.removeCallbacks(mPublishRunnable);
    }

    public void close() {
        mHandler.removeCallbacks(mPublishRunnable);
        mHandler.post(new Runnable() {

            @Override
            public void run() {
              /*  try {
                    mHttpTransport.shutdown();
                } catch (IOException e) {
                    Log.d(TAG, "error destroying http transport");
                } finally {
                    mHttpTransport = null;
                    mPubsub = null;
                }*/
            }
        });
        mHandlerThread.quitSafely();
    }

    private Runnable mPublishRunnable = new Runnable() {

        @Override
        public void run() {
            /*onnectivityManager connectivityManager =
                    (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
            if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting()) {
                Log.e(TAG, "no active network");
                return;
            }

            try {
                JSONObject messagePayload = createMessagePayload(mLastTemperature, mLastPressure);
                if (!messagePayload.has("data")) {
                    Log.d(TAG, "no sensor measurement to publish");
                    return;
                }
                Log.d(TAG, "publishing message: " + messagePayload);
                PubsubMessage m = new PubsubMessage();
                m.setData(Base64.encodeToString(messagePayload.toString().getBytes(),
                        Base64.NO_WRAP));
                PublishRequest request = new PublishRequest();
                request.setMessages(Collections.singletonList(m));
                mPubsub.projects().topics().publish(mTopic, request).execute();
            } catch (JSONException | IOException e) {
                Log.e(TAG, "Error publishing message", e);
            } finally {
                //post again a publish runnable to post new values on the cloud
                mHandler.postDelayed(mPublishRunnable, PUBLISH_INTERVAL_MS);
            }*/
        }

        /*private JSONObject createMessagePayload(float temperature, float pressure)
                throws JSONException {
            JSONObject sensorData = new JSONObject();
            if (!Float.isNaN(temperature)) {
                sensorData.put("temperature", String.valueOf(temperature));
            }
            if (!Float.isNaN(pressure)) {
                sensorData.put("pressure", String.valueOf(pressure));
            }
            JSONObject messagePayload = new JSONObject();
            messagePayload.put("deviceId", Build.DEVICE);
            messagePayload.put("channel", "pubsub");
            messagePayload.put("timestamp", System.currentTimeMillis());
            if (sensorData.has("temperature") || sensorData.has("pressure")) {
                messagePayload.put("data", sensorData);
            }
            return messagePayload;
        }*/
    };

    private SensorEventListener mTemperatureListener = new SensorEventListener() {

        @Override
        public void onSensorChanged(SensorEvent event) {
            mLastTemperature = event.values[0];
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    public SensorEventListener getTemperatureListener() {
        return mTemperatureListener;
    }

    private SensorEventListener mPressureListener = new SensorEventListener() {

        @Override
        public void onSensorChanged(SensorEvent event) {
            mLastPressure = event.values[0];
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    public SensorEventListener getPressureListener() {
        return mPressureListener;
    }
}