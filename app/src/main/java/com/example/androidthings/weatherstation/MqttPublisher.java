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
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;

import java.io.IOException;


//Adapted from https://github.com/eclipse/paho.mqtt.android/blob/master/paho.mqtt.android.example/src/main/java/paho/mqtt/java/example/PahoExampleActivity.java
public class MqttPublisher {
    private static final String TAG = MqttPublisher.class.getSimpleName();

    private final Context mContext;
    private final String mAppname;
    private final String mTopic;

    private Handler mHandler;
    private HandlerThread mHandlerThread;

    private float mLastTemperature = Float.NaN;
    private float mLastPressure = Float.NaN;

    //thingSpeak supports publishing every 15 seconds
    private static final long PUBLISH_INTERVAL_MS = 20000;

    private static final String MQTT_BROKER_URI = "ssl://mqtt.thingspeak.com:8883";
    private MqttAndroidClient mqttAndroidClient;
    private final MqttConnectOptions mqttConnectOptions;
    private String mMessagePayload;
    private static final String mPublishStatus = "MQTTPUBLISH";

    public MqttPublisher(Context context, String appname) throws IOException {
        mContext = context;
        mAppname = appname;
        mTopic = "channels/"+ BuildConfig.THINGSPEAK_CHANNEL_ID + "/publish/" + BuildConfig.THINGSPEAK_WRITE_API_KEY;

        //create a new thread and related Looper
        mHandlerThread = new HandlerThread("MQTTPublisherThread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mqttAndroidClient = new MqttAndroidClient(context, MQTT_BROKER_URI, MqttClient.generateClientId());
        mqttAndroidClient.setCallback(new MqttCallbackExtended() {

            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                Log.d(TAG, "Connected to: " + MQTT_BROKER_URI);
            }

            @Override
            public void connectionLost(Throwable cause) {
                Log.d(TAG, "Disconnected from: " + MQTT_BROKER_URI);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                Log.d(TAG, "Incoming message: " + new String(message.getPayload()));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                Log.d(TAG, "Delivery Complete");
            }
        });

        mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setAutomaticReconnect(true);
        mqttConnectOptions.setCleanSession(true);

        mHandler.post(new Runnable() {

            @Override
            public void run() {
                try {
                    mqttAndroidClient.connect(mqttConnectOptions, null, new IMqttActionListener() {

                        @Override
                        public void onSuccess(IMqttToken asyncActionToken) {
                            DisconnectedBufferOptions disconnectedBufferOptions = new DisconnectedBufferOptions();
                            disconnectedBufferOptions.setBufferEnabled(true);
                            disconnectedBufferOptions.setBufferSize(100);
                            disconnectedBufferOptions.setPersistBuffer(false);
                            disconnectedBufferOptions.setDeleteOldestMessages(false);
                            mqttAndroidClient.setBufferOpts(disconnectedBufferOptions);
                            mHandler.post(mPublishRunnable);
                        }

                        @Override
                        public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                            Log.d(TAG, "Failed to connect to: " + MQTT_BROKER_URI);
                        }
                    });
                } catch (MqttException ex){
                    Log.d(TAG, "Connection failed " + ex.toString());
                }
            }
        });
    }

    public void close() {
        mHandler.removeCallbacks(mPublishRunnable);
        mHandler.post(new Runnable() {

            @Override
            public void run() {
                try {
                    if(mqttAndroidClient.isConnected()) {
                        mqttAndroidClient.disconnect();
                    }
                } catch (MqttException e) {
                    Log.d(TAG, "Disconnection error" + e.toString());
                }
            }
        });
        mHandlerThread.quitSafely();
    }

    private Runnable mPublishRunnable = new Runnable() {

        @Override
        public void run() {
            ConnectivityManager connectivityManager =
                    (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
            if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting()) {
                Log.e(TAG, "no active network");
                return;
            }

            try {
                MqttMessage message = new MqttMessage();
                mMessagePayload = "field1=" + mLastTemperature + "&field2=" + mLastPressure + "&status=" + mPublishStatus;
                message.setPayload(mMessagePayload.getBytes());
                //parameters requested by ThingSpeak APIs
                message.setQos(0);
                message.setRetained(false);
                if(mqttAndroidClient != null) {
                    mqttAndroidClient.publish(mTopic, message);
                    Log.d(TAG, "Published new data");
                }

            } catch (MqttPersistenceException e) {
                e.printStackTrace();
            } catch (MqttException e) {
                e.printStackTrace();
            }
            mHandler.postDelayed(mPublishRunnable, PUBLISH_INTERVAL_MS);
        }
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