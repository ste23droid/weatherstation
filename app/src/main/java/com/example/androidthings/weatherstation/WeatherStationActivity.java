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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import com.google.android.things.contrib.driver.apa102.Apa102;
import com.google.android.things.contrib.driver.bmx280.Bmx280SensorDriver;
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.button.ButtonInputDriver;
import com.google.android.things.contrib.driver.ht16k33.AlphanumericDisplay;
import com.google.android.things.contrib.driver.pwmspeaker.Speaker;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.DecimalFormat;

import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

public class WeatherStationActivity extends Activity {

    private static final String TAG = WeatherStationActivity.class.getSimpleName();

    private enum DisplayMode {
        TEMPERATURE,
        PRESSURE
    }

    // https://developer.android.com/reference/android/hardware/SensorManager.html
    private SensorManager mSensorManager;

    private ButtonInputDriver mButtonInputDriverA;
    private ButtonInputDriver mButtonInputDriverB;
    private ButtonInputDriver mButtonInputDriverC;
    private Bmx280SensorDriver mEnvironmentalSensorDriver;
    private AlphanumericDisplay mDisplay;
    private DisplayMode mDisplayMode = DisplayMode.TEMPERATURE;

    private Apa102 mLedstrip;
    private int[] mRainbow = new int[7];
    private Gpio mLedRed;
    private Gpio mLedGreen;
    private Gpio mLedBlue;

    private static final int LEDSTRIP_BRIGHTNESS = 1;
    private static final float BAROMETER_RANGE_LOW = 965.f;
    private static final float BAROMETER_RANGE_HIGH = 1035.f;
    private static final float BAROMETER_RANGE_SUNNY = 1010.f;
    private static final float BAROMETER_RANGE_RAINY = 990.f;

    private int SPEAKER_READY_DELAY_MS = 300;
    private Speaker mSpeaker;

    private MqttPublisher mMqttPublisher;
    private ImageView mImageView;

    private static final int MSG_UPDATE_BAROMETER_UI = 1;
    public static final String CPU_FILE_PATH = "/sys/class/thermal/thermal_zone0/temp";
    private static final float HEATING_COEFFICIENT = 0.55f;
    private static final long UPDATE_CPU_DELAY = 50;

    private Float mCpuTemperature = 0f;
    private Observable<Float> mCpuTemperatureObservable;

    private Handler mCpuTemperatureHandler;
    private Handler mSoundHandler;

    private final Handler mUpdateUIHandler = new Handler() {
        private int mBarometerImage = -1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_BAROMETER_UI:
                    int img;
                    if (mLastPressure > BAROMETER_RANGE_SUNNY) {
                        img = R.drawable.ic_sunny;
                    } else if (mLastPressure < BAROMETER_RANGE_RAINY) {
                        img = R.drawable.ic_rainy;
                    } else {
                        img = R.drawable.ic_cloudy;
                    }
                    if (img != mBarometerImage) {
                        mImageView.setImageResource(img);
                        mBarometerImage = img;
                    }
                    break;
            }
        }
    };

    private float mLastTemperature;
    private float mLastPressure;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "Started Weather Station");

        setContentView(R.layout.activity_main);
        mImageView = (ImageView) findViewById(R.id.imageView);

        mSensorManager = ((SensorManager) getSystemService(SENSOR_SERVICE));

        // GPIO button that generates 'A' keypresses (handled by onKeyUp method)
        try {
            mButtonInputDriverA = new ButtonInputDriver(
                    BoardDefaults.RPI_BUTTON_A,
                    Button.LogicState.PRESSED_WHEN_LOW,
                    KeyEvent.KEYCODE_A);
            mButtonInputDriverA.register();
            Log.d(TAG, "Initialized GPIO Button that generates a keypress with KEYCODE_A");
        } catch (IOException e) {
            throw new RuntimeException("Error initializing GPIO button", e);
        }

        // GPIO button that generates 'B' keypresses (handled by onKeyUp method)
        try {
            mButtonInputDriverB = new ButtonInputDriver(
                    BoardDefaults.RPI_BUTTON_B,
                    Button.LogicState.PRESSED_WHEN_LOW,
                    KeyEvent.KEYCODE_B);
            mButtonInputDriverB.register();
            Log.d(TAG, "Initialized GPIO Button that generates a keypress with KEYCODE_B");
        } catch (IOException e) {
            throw new RuntimeException("Error initializing GPIO button", e);
        }

        // GPIO button that generates 'C' keypresses (handled by onKeyUp method)
        try {
            mButtonInputDriverC = new ButtonInputDriver(
                    BoardDefaults.RPI_BUTTON_C,
                    Button.LogicState.PRESSED_WHEN_LOW,
                    KeyEvent.KEYCODE_C);
            mButtonInputDriverC.register();
            Log.d(TAG, "Initialized GPIO Button that generates a keypress with KEYCODE_C");
        } catch (IOException e) {
            throw new RuntimeException("Error initializing GPIO button", e);
        }

        // I2C
        // Note: In this sample we only use one I2C bus, but multiple peripherals can be connected
        // to it and we can access them all, as long as they each have a different address on the
        // bus. Many peripherals can be configured to use a different address, often by connecting
        // the pins a certain way; this may be necessary if the default address conflicts with
        // another peripheral's. In our case, the temperature sensor and the display have
        // different default addresses, so everything just works.
        try {
            mEnvironmentalSensorDriver = new Bmx280SensorDriver(BoardDefaults.getI2cBus());
            //attach the sensor update callbacks to the SensorManager
            mSensorManager.registerDynamicSensorCallback(mDynamicSensorCallback);
            //start to get temperature and pressure data
            mEnvironmentalSensorDriver.registerTemperatureSensor();
            mEnvironmentalSensorDriver.registerPressureSensor();
            Log.d(TAG, "Initialized I2C BMP280");
        } catch (IOException e) {
            throw new RuntimeException("Error initializing BMP280", e);
        }

        //initialize display
        try {
            mDisplay = new AlphanumericDisplay(BoardDefaults.getI2cBus());
            mDisplay.setEnabled(true);
            mDisplay.display("Hi!!");
            mDisplay.clear();
            Log.d(TAG, "Initialized I2C Display");
        } catch (IOException e) {
            Log.e(TAG, "Error initializing display", e);
            Log.d(TAG, "Display disabled");
            mDisplay = null;
        }

        // SPI ledstrip
        try {
            mLedstrip = new Apa102(BoardDefaults.getSpiBus(), Apa102.Mode.BGR);
            mLedstrip.setBrightness(LEDSTRIP_BRIGHTNESS);
            for (int i = 0; i < mRainbow.length; i++) {
                float[] hsv = {i * 360.f / mRainbow.length, 1.0f, 1.0f};
                mRainbow[i] = Color.HSVToColor(255, hsv);
            }
        } catch (IOException e) {
            mLedstrip = null; // Led strip is optional.
        }

        PeripheralManagerService pioService = new PeripheralManagerService();

        // GPIO led red
        try {
            mLedRed = pioService.openGpio(BoardDefaults.RPI_LED_RED);
            mLedRed.setEdgeTriggerType(Gpio.EDGE_NONE);
            mLedRed.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mLedRed.setActiveType(Gpio.ACTIVE_HIGH);
        } catch (IOException e) {
            throw new RuntimeException("Error initializing led", e);
        }

        //GPIO led green
        try {
            mLedGreen = pioService.openGpio(BoardDefaults.RPI_LED_GREEN);
            mLedGreen.setEdgeTriggerType(Gpio.EDGE_NONE);
            mLedGreen.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mLedGreen.setActiveType(Gpio.ACTIVE_HIGH);
        } catch (IOException e) {
            throw new RuntimeException("Error initializing led", e);
        }

        //GPIO led blue
        try {
            mLedBlue = pioService.openGpio(BoardDefaults.RPI_LED_BLUE);
            mLedBlue.setEdgeTriggerType(Gpio.EDGE_NONE);
            mLedBlue.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mLedBlue.setActiveType(Gpio.ACTIVE_HIGH);
        } catch (IOException e) {
            throw new RuntimeException("Error initializing led", e);
        }

        // PWM speaker
        try {
            mSpeaker = new Speaker(BoardDefaults.getSpeakerPwmPin());
            //create a Handler to post board startup sounds on main thread
            playSound(5);
        } catch (IOException e) {
            throw new RuntimeException("Error initializing speaker", e);
        }

        mCpuTemperatureObservable = getCpuTemperatureObservable();
        mCpuTemperatureObservable.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread());

        //create a handler for main thread to post precise information on CPU temperature from file system
        mCpuTemperatureHandler = new Handler(getMainLooper());
        mCpuTemperatureHandler.post(mTemperatureRunnable);

        // start MQTT Publisher
            try {
                mMqttPublisher = new MqttPublisher(this, "weatherstation");
                mMqttPublisher.start();
            } catch (IOException e) {
                Log.e(TAG, "Error creating MQTT publisher", e);
            }
    }

    // Callback used when we register the BMP280 sensor driver with the system's SensorManager.
    // register 4 listeners: 2 to update the board informations, 2 to update information for the cloud
    private SensorManager.DynamicSensorCallback mDynamicSensorCallback = new SensorManager.DynamicSensorCallback() {

        @Override
        public void onDynamicSensorConnected(Sensor sensor) {
            if (sensor.getType() == Sensor.TYPE_AMBIENT_TEMPERATURE) {
                // Our sensor is connected. Start receiving temperature data.
                mSensorManager.registerListener(mTemperatureListener, sensor, SensorManager.SENSOR_DELAY_NORMAL);
                if (mMqttPublisher != null) {
                    //register a temperature listener to publish data on cloud when new temperature available
                    mSensorManager.registerListener(mMqttPublisher.getTemperatureListener(), sensor, SensorManager.SENSOR_DELAY_NORMAL);
                }
            } else if (sensor.getType() == Sensor.TYPE_PRESSURE) {
                // Our sensor is connected. Start receiving pressure data.
                mSensorManager.registerListener(mPressureListener, sensor, SensorManager.SENSOR_DELAY_NORMAL);
                if (mMqttPublisher != null) {
                    //register a pressure listener to publish data on cloud when new pressure available
                    mSensorManager.registerListener(mMqttPublisher.getPressureListener(), sensor, SensorManager.SENSOR_DELAY_NORMAL);
                }
            }
        }

        @Override
        public void onDynamicSensorDisconnected(Sensor sensor) {
            super.onDynamicSensorDisconnected(sensor);
        }
    };

    // Callback when SensorManager delivers temperature data.
    private SensorEventListener mTemperatureListener = new SensorEventListener() {

        @Override
        public void onSensorChanged(SensorEvent event) {
            mLastTemperature = event.values[0];
            Log.d(TAG, "sensor changed: " + mLastTemperature);

            if (mDisplayMode == DisplayMode.TEMPERATURE) {
                updateDisplayTemperature(mLastTemperature);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            Log.d(TAG, "accuracy changed: " + accuracy);
        }
    };

    // Callback when SensorManager delivers pressure data.
    private SensorEventListener mPressureListener = new SensorEventListener() {

        @Override
        public void onSensorChanged(SensorEvent event) {
            mLastPressure = event.values[0];
            Log.d(TAG, "sensor changed: " + mLastPressure);

            if (mDisplayMode == DisplayMode.PRESSURE) {
                updateDisplayPressure(mLastPressure);
            }
            updateLedStrip(mLastPressure);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            Log.d(TAG, "accuracy changed: " + accuracy);
        }
    };

    /**
     * Creates an observable which reads the CPU temperature from the file system.
     *
     * @return the observable
     */
    private Observable<Float> getCpuTemperatureObservable() {
        return Observable.create(new Observable.OnSubscribe<Float>() {

            @Override
            public void call(Subscriber<? super Float> subscriber) {
                RandomAccessFile reader = null;
                try {
                    reader = new RandomAccessFile(CPU_FILE_PATH, "r");
                    String rawTemperature = reader.readLine();
                    //temperature on the file is in milli celsius, we want celsius
                    float cpuTemperature = Float.parseFloat(rawTemperature) / 1000f;
                    Log.i(WeatherStationActivity.TAG, "Parsed temp: " + cpuTemperature);
                    subscriber.onNext(cpuTemperature);
                } catch (IOException ex) {
                    ex.printStackTrace();
                    subscriber.onError(ex);
                } finally {
                    if(reader != null) {
                        try {
                            reader.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                            subscriber.onError(e);
                        }
                    }
                }
            }
        });
    }

    private Runnable mTemperatureRunnable = new Runnable() {

        @Override
        public void run() {
            //create a subscriber and subscribe to the CPU temperature observable
            mCpuTemperatureObservable.subscribe(new Subscriber<Float>() {

                @Override
                public void onCompleted() {
                    Log.e(TAG, "Completed.");
                }

                @Override
                public void onError(Throwable e) {
                    Log.e(TAG, "Error: " + e.getMessage());
                }

                @Override
                public void onNext(Float resultCpuTemperature) {
                    //save the CPU temperature read from file system
                    mCpuTemperature = resultCpuTemperature;
                }
            });

            //post again a message in the main thread message queue, to update again the temperature in future
            mCpuTemperatureHandler.postDelayed(mTemperatureRunnable, UPDATE_CPU_DELAY);
        }
    };

    private void playSound(int repetitions) {
        final ValueAnimator soundAnimator = ValueAnimator.ofFloat(440, 440 * 4);
        soundAnimator.setDuration(50);
        soundAnimator.setRepeatCount(repetitions);
        soundAnimator.setInterpolator(new LinearInterpolator());
        soundAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {

            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                try {
                    float v = (float) animation.getAnimatedValue();
                    mSpeaker.play(v);
                } catch (IOException e) {
                    throw new RuntimeException("Error sliding speaker", e);
                }
            }
        });
        soundAnimator.addListener(new AnimatorListenerAdapter() {

            @Override
            public void onAnimationEnd(Animator animation) {
                try {
                    mSpeaker.stop();
                } catch (IOException e) {
                    throw new RuntimeException("Error sliding speaker", e);
                }
            }
        });

        //create a Handler to post sound messages in the Message queue of the main thread looper
        mSoundHandler = new Handler(getMainLooper());
        mSoundHandler.postDelayed(new Runnable() {

            @Override
            public void run() {
                soundAnimator.start();
            }
        }, SPEAKER_READY_DELAY_MS);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(keyCode == KeyEvent.KEYCODE_A) {
            if(mDisplayMode != DisplayMode.TEMPERATURE) {
                mDisplayMode = DisplayMode.TEMPERATURE;
                updateDisplayTemperature(mLastTemperature);
                try {
                    mLedRed.setValue(true);
                } catch (IOException e) {
                    Log.e(TAG, "error updating LED", e);
                }
                return true;
            }
        } else if(keyCode == KeyEvent.KEYCODE_B) {
            if(mDisplayMode != DisplayMode.PRESSURE) {
                mDisplayMode = DisplayMode.PRESSURE;
                updateDisplayPressure(mLastPressure);
                try {
                    mLedBlue.setValue(true);
                } catch (IOException e) {
                    Log.e(TAG, "error updating LED", e);
                }
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_A) {
            try {
                mLedRed.setValue(false);
            } catch (IOException e) {
                Log.e(TAG, "error updating LED", e);
            }
            return true;
        } else if(keyCode == KeyEvent.KEYCODE_B) {
            try {
                mLedBlue.setValue(false);
            } catch (IOException e) {
                Log.e(TAG, "error updating LED", e);
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private void updateDisplayTemperature(float temperature) {
        if (mDisplay != null) {
            try {
                // display modified temperature given HAT and CPU proximity
                //formula taken from here: https://hackernoon.com/trying-out-the-android-things-weatherstation-codelab-d3f260b59c2f
                if(mCpuTemperature > temperature) {
                    temperature = (temperature - HEATING_COEFFICIENT * mCpuTemperature) / (1 - HEATING_COEFFICIENT);
                }
                mDisplay.display(new DecimalFormat("##").format(temperature));
            } catch (IOException e) {
                Log.e(TAG, "Error setting display", e);
            }
        }
    }

    private void updateDisplayPressure(float pressure) {
        if (mDisplay != null) {
            try {
                mDisplay.display(pressure);
            } catch (IOException e) {
                Log.e(TAG, "Error setting display", e);
            }
        }
    }

    private void updateLedStrip(float pressure) {
        // Update UI
        if (!mUpdateUIHandler.hasMessages(MSG_UPDATE_BAROMETER_UI)) {
            mUpdateUIHandler.sendEmptyMessageDelayed(MSG_UPDATE_BAROMETER_UI, 100);
        }

        // Update led strip.
        if (mLedstrip == null) {
            return;
        }
        float t = (pressure - BAROMETER_RANGE_LOW) / (BAROMETER_RANGE_HIGH - BAROMETER_RANGE_LOW);
        int n = (int) Math.ceil(mRainbow.length * t);
        n = Math.max(0, Math.min(n, mRainbow.length));
        int[] colors = new int[mRainbow.length];
        for (int i = 0; i < n; i++) {
            int ri = mRainbow.length - 1 - i;
            colors[ri] = mRainbow[ri];
        }
        try {
            mLedstrip.write(colors);
        } catch (IOException e) {
            Log.e(TAG, "Error setting ledstrip", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Clean up sensor registrations
        mSensorManager.unregisterListener(mTemperatureListener);
        mSensorManager.unregisterListener(mPressureListener);
        mSensorManager.unregisterDynamicSensorCallback(mDynamicSensorCallback);

        // Clean up peripheral.
        if (mEnvironmentalSensorDriver != null) {
            try {
                mEnvironmentalSensorDriver.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mEnvironmentalSensorDriver = null;
        }

        if (mButtonInputDriverA != null) {
            try {
                mButtonInputDriverA.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mButtonInputDriverA = null;
        }

        if (mButtonInputDriverB != null) {
            try {
                mButtonInputDriverB.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mButtonInputDriverB = null;
        }

        if (mButtonInputDriverC != null) {
            try {
                mButtonInputDriverC.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mButtonInputDriverC = null;
        }

        if (mDisplay != null) {
            try {
                mDisplay.clear();
                mDisplay.setEnabled(false);
                mDisplay.close();
            } catch (IOException e) {
                Log.e(TAG, "Error disabling display", e);
            } finally {
                mDisplay = null;
            }
        }

        if (mLedstrip != null) {
            try {
                mLedstrip.setBrightness(0);
                mLedstrip.write(new int[7]);
                mLedstrip.close();
            } catch (IOException e) {
                Log.e(TAG, "Error disabling ledstrip", e);
            } finally {
                mLedstrip = null;
            }
        }

        if (mLedRed != null) {
            try {
                mLedRed.setValue(false);
                mLedRed.close();
            } catch (IOException e) {
                Log.e(TAG, "Error disabling led", e);
            } finally {
                mLedRed = null;
            }
        }

        if (mLedGreen != null) {
            try {
                mLedGreen.setValue(false);
                mLedGreen.close();
            } catch (IOException e) {
                Log.e(TAG, "Error disabling led", e);
            } finally {
                mLedGreen = null;
            }
        }

        if (mLedBlue != null) {
            try {
                mLedBlue.setValue(false);
                mLedBlue.close();
            } catch (IOException e) {
                Log.e(TAG, "Error disabling led", e);
            } finally {
                mLedBlue = null;
            }
        }

        // clean up MQTT PubSub publisher.
        if (mMqttPublisher != null) {
            mSensorManager.unregisterListener(mMqttPublisher.getTemperatureListener());
            mSensorManager.unregisterListener(mMqttPublisher.getPressureListener());
            mMqttPublisher.close();
            mMqttPublisher = null;
        }
    }

}
