package rs.readahead.washington.mobile.views.activity;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.Task;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;

import org.witness.proofmode.util.DeviceInfo;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.observers.DisposableObserver;
import io.reactivex.subjects.BehaviorSubject;
import rs.readahead.washington.mobile.R;
import rs.readahead.washington.mobile.data.sharedpref.Preferences;
import rs.readahead.washington.mobile.domain.entity.Metadata;
import rs.readahead.washington.mobile.domain.entity.MyLocation;
import rs.readahead.washington.mobile.mvp.contract.IMetadataAttachPresenterContract;
import rs.readahead.washington.mobile.presentation.entity.SensorData;
import rs.readahead.washington.mobile.util.DialogsUtil;
import rs.readahead.washington.mobile.util.LocationUtil;
import rs.readahead.washington.mobile.util.TelephonyUtils;
import timber.log.Timber;


public abstract class MetadataActivity extends CacheWordSubscriberBaseActivity implements
        SensorEventListener {
    private static final long LOCATION_REQUEST_INTERVAL = 5000; // aggressive

    private SensorManager mSensorManager;
    private Sensor mLight;
    private Sensor mAmbientTemperature;

    private FusedLocationProviderClient fusedLocationProviderClient;
    private LocationCallback locationCallback;
    private WifiManager wifiManager;
    private BroadcastReceiver wifiScanResultReceiver;

    private static Location currentBestLocation;

    private boolean locationListenerRegistered = false;
    private boolean wifiReceiverRegistered = false;

    private boolean sensorListenerRegistered = false;
    private static SensorData lightSensorData = new SensorData();
    private static SensorData ambientTemperatureSensorData = new SensorData();

    private final BehaviorSubject<List<String>> wifiSubject = BehaviorSubject.create();
    private final static BehaviorSubject<MyLocation> locationSubject = BehaviorSubject.create();
    private LocationManager locationManager;

    private AlertDialog metadataAlertDialog;
    private AlertDialog locationAlertDialog;
    private Relay<MetadataHolder> metadataCancelRelay;
    private CompositeDisposable disposables;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Sensors
        mSensorManager = (SensorManager) getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        //noinspection ConstantConditions
        mLight = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        mAmbientTemperature = mSensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE);

        // Location
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        locationCallback = new MetadataLocationCallback();

        // Wifi
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiScanResultReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                wifiSubject.onNext(getWifiStrings(wifiManager.getScanResults()));
            }
        };

        // UI stuff
        metadataCancelRelay = PublishRelay.create();
        disposables = new CompositeDisposable();
    }

    private List<String> getWifiStrings(List<ScanResult> results) {
        List<String> wifiStrings = new ArrayList<>(results.size());

        for (ScanResult result: results) {
            wifiStrings.add(result.SSID);
        }

        return wifiStrings;
    }

    protected void startSensorListening() {
        if (Preferences.isAnonymousMode()) {
            return;
        }

        mSensorManager.registerListener(this, mLight, SensorManager.SENSOR_DELAY_NORMAL);
        mSensorManager.registerListener(this, mAmbientTemperature, SensorManager.SENSOR_DELAY_NORMAL);
        sensorListenerRegistered = true;
    }

    protected void startLocationMetadataListening() {
        if (Preferences.isAnonymousMode()) {
            return;
        }

        startLocationListening();
        startWifiListening();
    }

    @SuppressWarnings("MissingPermission") // we have check
    private synchronized void startLocationListening() {
        if (isFineLocationPermissionDenied()) {
            return;
        }

        // google services way..
        fusedLocationProviderClient.requestLocationUpdates(createLocationRequest(), locationCallback, null);
        locationListenerRegistered = true;

        // get last known location to start with..
        getLastLocation();
    }

    @SuppressWarnings("MissingPermission") // we have check
    private void getLastLocation() {
        if (Preferences.isAnonymousMode()) {
            return;
        }

        if (isFineLocationPermissionDenied()) {
            return;
        }

        fusedLocationProviderClient.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        acceptBetterLocation(location);
                    }
                });
    }

    private synchronized void startWifiListening() {
        if (isFineLocationPermissionDenied()) {
            return;
        }

        if (wifiManager == null || wifiReceiverRegistered) {
            return;
        }

        // put what you know in subject..
        wifiSubject.onNext(getWifiStrings(wifiManager.getScanResults()));

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);

        registerReceiver(wifiScanResultReceiver, filter);
        wifiReceiverRegistered = true;
    }

    protected synchronized void startWifiScan() {
        if (Preferences.isAnonymousMode()) {
            return;
        }

        if (wifiManager != null && wifiReceiverRegistered) {
            wifiManager.startScan();
        }
    }

    private void stopSensorListening() {
        if (! sensorListenerRegistered) {
            return;
        }

        mSensorManager.unregisterListener(this);
        sensorListenerRegistered = false;
    }

    protected void stopLocationMetadataListening() {
        stopLocationListening();
        stopWifiListening();
    }

    private synchronized void stopLocationListening() {
        if (! locationListenerRegistered) {
            return;
        }

        fusedLocationProviderClient.removeLocationUpdates(locationCallback)
                .addOnCompleteListener(task -> locationListenerRegistered = false);
    }

    private synchronized void stopWifiListening() {
        if (! wifiReceiverRegistered) {
            return;
        }

        unregisterReceiver(wifiScanResultReceiver);
        wifiReceiverRegistered = false;
    }

    @Override
    protected void onResume() {
        super.onResume();

        startSensorListening();
    }

    @Override
    protected void onPause() {
        super.onPause();

        stopSensorListening();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        Sensor sensor = event.sensor;

        if (sensor.getType() == Sensor.TYPE_LIGHT) {
            lightSensorData.setValue(event.timestamp, event.values[0]);
        } else if (sensor.getType() == Sensor.TYPE_AMBIENT_TEMPERATURE) {
            ambientTemperatureSensorData.setValue(event.timestamp, event.values[0]);
        }
    }

    @Override
    protected void onDestroy() {
        if (disposables != null) {
            disposables.dispose();
        }
        hideLocationAlertDialog();
        wifiSubject.onComplete();

        super.onDestroy();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private boolean isFineLocationPermissionDenied() {
        return (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED);
    }

    private boolean isLocationProviderEnabled() {
        final LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            return false;
        }

        // if we have GPS, we have location gathering..
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    private LocationRequest createLocationRequest() {
        LocationRequest locationRequest = new LocationRequest();
        locationRequest.setInterval(LOCATION_REQUEST_INTERVAL);
        locationRequest.setFastestInterval(LOCATION_REQUEST_INTERVAL);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        return locationRequest;
    }

    interface LocationSettingsCheckDoneListener {
        void onContinue();
    }

    protected void checkLocationSettings(final int requestCode, final LocationSettingsCheckDoneListener listener) {
        if (isFineLocationPermissionDenied()) {
            listener.onContinue();
            return;
        }

        if (!Preferences.isAnonymousMode() && !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            showGpsMetadataDialog(requestCode, listener);
        } else {
            listener.onContinue();
        }
    }

    private void manageLocationSettings(final int requestCode, final LocationSettingsCheckDoneListener listener){
        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder();
        builder.addLocationRequest(createLocationRequest());

        SettingsClient client = LocationServices.getSettingsClient(this);
        Task<LocationSettingsResponse> task = client.checkLocationSettings(builder.build());

        task.addOnSuccessListener(this, locationSettingsResponse -> listener.onContinue());

        task.addOnFailureListener(this, e -> {
            int statusCode = ((ApiException) e).getStatusCode();
            switch (statusCode) {
                case CommonStatusCodes.RESOLUTION_REQUIRED:
                    try {
                        ResolvableApiException resolvable = (ResolvableApiException) e;
                        resolvable.startResolutionForResult(MetadataActivity.this, requestCode);
                    } catch (IntentSender.SendIntentException ignored) {
                    }
                    break;
                case LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE:
                    listener.onContinue();
                    break;
            }
        });
    }

    private void showGpsMetadataDialog(final int requestCode, final LocationSettingsCheckDoneListener listener) {
        String message = getString(R.string.gps_metadata_dialog_info);

        locationAlertDialog = DialogsUtil.showMessageOKCancelWithTitle(this,
                message,
                getString(R.string.attention),
                getString(R.string.ignore),
                getString(R.string.turn_on_gps),
                (dialog, which) -> {  //ignore
                    dialog.dismiss();
                    listener.onContinue();
                },
                (dialog, which) -> {  //turn on gps
                    manageLocationSettings(requestCode, listener);
                    dialog.dismiss();
                });
    }

    public SensorData getLightSensorData() {
        return lightSensorData;
    }

    public SensorData getAmbientTemperatureSensorData() {
        return ambientTemperatureSensorData;
    }

    public Observable<List<String>> observeWifiData() {
        return wifiSubject;
    }

    public Observable<MyLocation> observeLocationData() {
        return locationSubject;
    }

    /**
     * Will emit combined object consisting of emitted both wifi and location data
     * combined, each time one of them changes. If there is no data for one of them,
     * empty data is in MetadataHolder object.
     *
     * @return stream of metadata holder objects
     */
    public Observable<MetadataHolder> observeMetadata() {
        return Observable.combineLatest(
                    observeLocationData().startWith(MyLocation.createEmpty()),
                    observeWifiData().startWith(Collections.<String>emptyList()),
                    MetadataHolder::new
                )
                .filter(mh -> (!mh.getWifis().isEmpty() || !mh.getLocation().isEmpty()))
                .take((5 * 60 * 1000) / LOCATION_REQUEST_INTERVAL) // approx max 5 min of trying limit
                .takeUntil(mh -> !mh.getWifis().isEmpty() && !mh.getLocation().isEmpty());
    }

    private static class MetadataLocationCallback extends LocationCallback {
        @Override
        public void onLocationResult(LocationResult locationResult) {
            Location location = locationResult.getLastLocation();
            acceptBetterLocation(location);
        }
    }

    private static void acceptBetterLocation(Location location) {
        if (! LocationUtil.isBetterLocation(location, currentBestLocation)) {
            return;
        }

        currentBestLocation = location;
        locationSubject.onNext(MyLocation.fromLocation(location));
    }

    // UI stuff
    protected void attachMediaFileMetadata(final long mediaFileId, final IMetadataAttachPresenterContract.IPresenter metadataAttacher) {
        // skip metadata if anonymous mode..
        if (Preferences.isAnonymousMode()) {
            metadataAttacher.attachMetadata(mediaFileId, null);
            return;
        }

        final Metadata metadata = new Metadata();

        // set basic metadata
        metadata.setTimestamp(System.currentTimeMillis() / 1000L);
        metadata.setAmbientTemperature(getAmbientTemperatureSensorData().hasValue() ? getAmbientTemperatureSensorData().getValue() : null);
        metadata.setLight(getLightSensorData().hasValue() ? getLightSensorData().getValue() : null);

        // get simple PM metadata
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.FULL,DateFormat.FULL);
        metadata.setFileModified(df.format(new Date())); // do we are generating file and metadata at the same time
        metadata.setProofGenerated(df.format(new Date()));

        //if (showDeviceIds) {
        metadata.setDeviceID(DeviceInfo.getDeviceId(getBaseContext()));
        metadata.setWifiMac(DeviceInfo.getWifiMacAddr());
        //}
        metadata.setIPv4(DeviceInfo.getDeviceInfo(getBaseContext(), DeviceInfo.Device.DEVICE_IP_ADDRESS_IPV4));
        metadata.setIPv6(DeviceInfo.getDeviceInfo(getBaseContext(), DeviceInfo.Device.DEVICE_IP_ADDRESS_IPV6));

        metadata.setDataType(DeviceInfo.getDataType(getBaseContext()));
        metadata.setNetwork(DeviceInfo.getDeviceInfo(getBaseContext(), DeviceInfo.Device.DEVICE_NETWORK));

        metadata.setNetworkType(DeviceInfo.getNetworkType(getBaseContext()));
        metadata.setHardware(DeviceInfo.getDeviceInfo(getBaseContext(), DeviceInfo.Device.DEVICE_HARDWARE_MODEL));
        metadata.setManufacturer(DeviceInfo.getDeviceInfo(getBaseContext(), DeviceInfo.Device.DEVICE_MANUFACTURE));
        metadata.setScreenSize(DeviceInfo.getDeviceInch(getBaseContext()));

        metadata.setLanguage(DeviceInfo.getDeviceInfo(getBaseContext(), DeviceInfo.Device.DEVICE_LANGUAGE));
        metadata.setLocale(DeviceInfo.getDeviceInfo(getBaseContext(), DeviceInfo.Device.DEVICE_LOCALE));

        try {
            metadata.setCellInfo(DeviceInfo.getCellInfo(getBaseContext()));
        } catch (Exception e) {
            Timber.d(e);
        }
        // set cells
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            metadata.setCells(TelephonyUtils.getCellInfo(this));
        }

        // if location gathering is not possible skip it
        if (!isLocationProviderEnabled()) {
            metadataAttacher.attachMetadata(mediaFileId, metadata);
            return;
        }

        // wait for set location metadata
        disposables.add(observeMetadata()
                .subscribeOn(AndroidSchedulers.mainThread())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe(disposable -> showMetadataProgressBarDialog())
                .takeUntil(metadataCancelRelay) // this observable emits when user press skip in dialog.
                .doFinally(this::hideMetadataProgressBarDialog)
                .subscribeWith(new DisposableObserver<MetadataHolder>() {
                    @Override
                    public void onNext(MetadataActivity.MetadataHolder value) {
                        if (! value.getWifis().isEmpty()) {
                            metadata.setWifis(value.getWifis());
                        }

                        if (! value.getLocation().isEmpty()) {
                            metadata.setMyLocation(value.getLocation());
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        onComplete();
                    }

                    @Override
                    public void onComplete() {
                        metadataAttacher.attachMetadata(mediaFileId, metadata);
                    }
                })
        );
    }

    @SuppressWarnings("MethodOnlyUsedFromInnerClass")
    protected void showMetadataProgressBarDialog() {
        metadataAlertDialog = DialogsUtil.showMetadataProgressBarDialog(this, (dialog, which) -> {
            metadataCancelRelay.accept(MetadataHolder.createEmpty()); // :)
        });
    }

    @SuppressWarnings("MethodOnlyUsedFromInnerClass")
    protected void hideMetadataProgressBarDialog() {
        if (metadataAlertDialog != null) {
            metadataAlertDialog.dismiss();
        }
    }

    protected void hideLocationAlertDialog() {
        if (locationAlertDialog != null) {
            locationAlertDialog.dismiss();
        }
    }

    // Helper Classes
    static class MetadataHolder {
        private MyLocation location;
        private List<String> wifis;


        MetadataHolder(MyLocation location, List<String> wifis) {
            this.location = location;
            setWifis(wifis);
        }

        MyLocation getLocation() {
            return location;
        }

        List<String> getWifis() {
            return wifis;
        }

        private void setWifis(final List<String> wifis) {
            this.wifis = new ArrayList<>();

            for (String wifi: wifis) {
                if (! this.wifis.contains(wifi)) {
                    this.wifis.add(wifi);
                }
            }
        }

        static MetadataHolder createEmpty() {
            return new MetadataHolder(MyLocation.createEmpty(), Collections.emptyList());
        }
    }
}