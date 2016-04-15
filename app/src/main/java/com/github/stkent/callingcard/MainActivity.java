package com.github.stkent.callingcard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.Switch;
import android.widget.TextView;

import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.messages.Message;
import com.google.android.gms.nearby.messages.MessageListener;
import com.google.android.gms.nearby.messages.PublishCallback;
import com.google.android.gms.nearby.messages.PublishOptions;
import com.google.android.gms.nearby.messages.SubscribeCallback;
import com.google.android.gms.nearby.messages.SubscribeOptions;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import butterknife.Bind;
import butterknife.ButterKnife;

public class MainActivity extends BaseActivity
        implements ConnectionCallbacks, OnConnectionFailedListener, OnCheckedChangeListener {

    private static final String TAG = "MainActivity";

    private static final String USER_NAME_EXTRA_KEY = "USER_NAME_EXTRA_KEY";
    private static final String USER_EMAIL_ADDRESS_EXTRA_KEY = "USER_EMAIL_ADDRESS_EXTRA_KEY";
    private static final int PUBLISHING_ERROR_RESOLUTION_CODE = 5321;
    private static final int SUBSCRIBING_ERROR_RESOLUTION_CODE = 6546;

    protected static void launchWithCredentials(
            @NonNull final String userName,
            @NonNull final String userEmailAddress,
            @NonNull final Context context) {

        final Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(USER_NAME_EXTRA_KEY, userName);
        intent.putExtra(USER_EMAIL_ADDRESS_EXTRA_KEY, userEmailAddress);
        context.startActivity(intent);
    }

    private final PublishCallback publishCallback = new PublishCallback() {
        // Note: this seems to be invoked on a background thread!
        @Override
        public void onExpired() {
            /*
             * From https://developers.google.com/nearby/messages/android/pub-sub:
             *
             *   When actively publishing and subscribing, a "Nearby is in use" notification is
             *   presented, informing users that Nearby is active. This notification is only
             *   displayed when one or more apps are actively using Nearby, giving users a chance
             *   to conserve battery life if Nearby is not needed. It provides users with the
             *   following options:
             *
             *     - Navigate to an app to disable Nearby.
             *     - Force an app to stop using Nearby.
             *     - Navigate to the Nearby Settings screen.
             *
             *   You can use PublishCallback() [and SubscribeCallback()] to listen for cases when a
             *   user forces the app to stop using Nearby. When this happens, the onExpired()
             *   method is triggered.
             */
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cancelAllNearbyOperations();
                }
            });
        }
    };

    private final SubscribeCallback subscribeCallback = new SubscribeCallback() {
        // All comments in publishCallback apply here too.
        @Override
        public void onExpired() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cancelAllNearbyOperations();
                }
            });
        }
    };

    private final PublishOptions publishOptions
            = new PublishOptions.Builder().setCallback(publishCallback).build();

    private final SubscribeOptions subscribeOptions
            = new SubscribeOptions.Builder().setCallback(subscribeCallback).build();

    private final MessageListener messageListener = new MessageListener() {
        @Override
        public void onFound(final Message message) {
            try {
                final DeviceData deviceData
                        = new Gson().fromJson(new String(message.getContent()), DeviceData.class);

                receivedDeviceDataView.addDeviceData(deviceData);
            } catch (final JsonSyntaxException ignored) {
                toastError("Invalid message received!");
            }
        }

        @Override
        public void onLost(final Message message) {
            try {
                final DeviceData deviceData
                        = new Gson().fromJson(new String(message.getContent()), DeviceData.class);

                receivedDeviceDataView.removeDeviceData(deviceData);
            } catch (final JsonSyntaxException ignored) {
                toastError("Invalid message reported as lost!");
            }
        }
    };

    @Bind(R.id.publishing_switch)
    protected Switch publishingSwitch;

    @Bind(R.id.published_message_field)
    protected TextView publishedMessageField;

    @Bind(R.id.subscribing_switch)
    protected Switch subscribingSwitch;

    @Bind(R.id.received_device_data_view)
    protected ReceivedDeviceDataView receivedDeviceDataView;

    private Message messageToPublish;
    private GoogleApiClient nearbyGoogleApiClient;
    private boolean attemptingToPublish = false;
    private boolean attemptingToSubscribe = false;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        publishingSwitch.setOnCheckedChangeListener(this);
        subscribingSwitch.setOnCheckedChangeListener(this);

        final DeviceData deviceData = new DeviceData(this);
        publishedMessageField.setText(deviceData.toString());

        messageToPublish = new Message(new Gson().toJson(deviceData).getBytes());

        nearbyGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Nearby.MESSAGES_API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        final MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_sign_out:
                if (signInGoogleApiClient.isConnected()) {
                    Auth.GoogleSignInApi.signOut(signInGoogleApiClient).setResultCallback(
                            new ResultCallback<Status>() {
                                @Override
                                public void onResult(@NonNull final Status status) {
                                    cancelAllNearbyOperations();

                                    MainActivity.this.startActivity(
                                            new Intent(MainActivity.this, SignInActivity.class));

                                    finish();
                                }
                            });
                } else if (signInGoogleApiClient.isConnecting()) {
                    toastSignOutFailedError();
                } else {
                    signInGoogleApiClient.connect();
                    toastSignOutFailedError();
                }

                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (!nearbyGoogleApiClient.isConnected() && !nearbyGoogleApiClient.isConnecting()) {
            nearbyGoogleApiClient.connect();
        }
    }

    @Override
    protected void onStop() {
        cancelAllNearbyOperations();
        super.onStop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PUBLISHING_ERROR_RESOLUTION_CODE) {
            if (attemptingToPublish && resultCode == Activity.RESULT_OK) {
                // User was presented with the Nearby opt-in dialog and pressed "Allow".
                attemptToPublish();
            } else {
                // User declined to opt-in.
                publishingSwitch.setChecked(false);
            }

            attemptingToPublish = false;
        } else if (requestCode == SUBSCRIBING_ERROR_RESOLUTION_CODE) {
            if (attemptingToSubscribe && resultCode == Activity.RESULT_OK) {
                // User was presented with the Nearby opt-in dialog and pressed "Allow".
                attemptToSubscribe();
            } else {
                // User declined to opt-in.
                subscribingSwitch.setChecked(false);
            }

            attemptingToSubscribe = false;
        }
    }

    @Override
    public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked) {
        switch (buttonView.getId()) {
            case R.id.publishing_switch:
                if (publishingSwitch.isChecked()) {
                    if (nearbyGoogleApiClient.isConnected()) {
                        attemptToPublish();
                    } else if (!nearbyGoogleApiClient.isConnecting()) {
                        nearbyGoogleApiClient.connect();
                    }
                } else {
                    stopPublishing();
                    attemptingToPublish = false;
                }

                break;
            case R.id.subscribing_switch:
                if (subscribingSwitch.isChecked()) {
                    if (nearbyGoogleApiClient.isConnected()) {
                        attemptToSubscribe();
                    } else if (!nearbyGoogleApiClient.isConnecting()) {
                        nearbyGoogleApiClient.connect();
                    }
                } else {
                    stopSubscribing();
                    attemptingToSubscribe = false;
                }

                break;
            default:
                break;
        }
    }

    @Override
    public void onConnected(@Nullable final Bundle bundle) {
        syncSwitchStateWithGoogleApiClientState();

        if (publishingSwitch.isChecked()) {
            attemptToPublish();
        }

        if (subscribingSwitch.isChecked()) {
            attemptToSubscribe();
        }
    }

    @Override
    public void onConnectionSuspended(final int i) {
        handleGoogleApiClientConnectionIssue();
        // TODO: all usual error handling and resolution goes here
    }

    @Override
    public void onConnectionFailed(@NonNull final ConnectionResult connectionResult) {
        super.onConnectionFailed(connectionResult);

        handleGoogleApiClientConnectionIssue();
        // TODO: all usual error handling and resolution goes here
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }

    private void handleGoogleApiClientConnectionIssue() {
        syncSwitchStateWithGoogleApiClientState();
        publishingSwitch.setChecked(false);
        subscribingSwitch.setChecked(false);
    }

    private void cancelAllNearbyOperations() {
        publishingSwitch.setChecked(false);
        subscribingSwitch.setChecked(false);

        if (nearbyGoogleApiClient.isConnected() || nearbyGoogleApiClient.isConnecting()) {
            nearbyGoogleApiClient.disconnect();
        }

        receivedDeviceDataView.clearAllDeviceData();
    }

    private void attemptToPublish() {
        attemptingToPublish = true;

        Nearby.Messages.publish(nearbyGoogleApiClient, messageToPublish, publishOptions)
                .setResultCallback(new ResultCallback<Status>() {
                    @Override
                    public void onResult(@NonNull final Status status) {
                        if (status.isSuccess()) {
                            attemptingToPublish = false;
                        } else if (status.hasResolution()) {
                            try {
                                status.startResolutionForResult(
                                        MainActivity.this, PUBLISHING_ERROR_RESOLUTION_CODE);

                            } catch (final IntentSender.SendIntentException e) {
                                attemptingToPublish = false;
                                toastError(status.getStatusMessage());
                            }
                        } else {
                            attemptingToPublish = false;
                            toastError(status.getStatusMessage());
                            // TODO: error-specific handling if desired
                        }
                    }
                });
    }

    private void stopPublishing() {
        // TODO: check PendingResult of this call and retry if it is not a success?
        Nearby.Messages.unpublish(nearbyGoogleApiClient, messageToPublish);
    }

    private void attemptToSubscribe() {
        attemptingToSubscribe = true;

        Nearby.Messages.subscribe(nearbyGoogleApiClient, messageListener, subscribeOptions)
                .setResultCallback(new ResultCallback<Status>() {
                    @Override
                    public void onResult(@NonNull final Status status) {
                        if (status.isSuccess()) {
                            attemptingToSubscribe = false;
                        } else if (status.hasResolution()) {
                            try {
                                status.startResolutionForResult(
                                        MainActivity.this, SUBSCRIBING_ERROR_RESOLUTION_CODE);

                            } catch (final IntentSender.SendIntentException e) {
                                attemptingToSubscribe = false;
                                toastError(status.getStatusMessage());
                            }
                        } else {
                            attemptingToSubscribe = false;
                            toastError(status.getStatusMessage());
                            // TODO: error-specific handling if desired
                        }
                    }
                });
    }

    private void stopSubscribing() {
        // TODO: check PendingResult of this call and retry if it is not a success?
        Nearby.Messages.unsubscribe(nearbyGoogleApiClient, messageListener);
        receivedDeviceDataView.clearAllDeviceData();
    }

    private void syncSwitchStateWithGoogleApiClientState() {
        final boolean googleApiClientConnected = nearbyGoogleApiClient.isConnected();

        publishingSwitch.setEnabled(googleApiClientConnected);
        subscribingSwitch.setEnabled(googleApiClientConnected);
    }

    private void toastSignOutFailedError() {
        toastError("Sign out failed, please try again.");
    }

}
