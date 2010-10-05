/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone.sip;

import com.android.phone.R;
import com.android.phone.SipUtil;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.sip.SipException;
import android.net.sip.SipErrorCode;
import android.net.sip.SipProfile;
import android.net.sip.SipManager;
import android.net.sip.SipRegistrationListener;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Process;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.Button;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The PreferenceActivity class for managing sip profile preferences.
 */
public class SipSettings extends PreferenceActivity {
    public static final String SIP_SHARED_PREFERENCES = "SIP_PREFERENCES";

    static final String KEY_SIP_PROFILE = "sip_profile";

    private static final String BUTTON_SIP_RECEIVE_CALLS =
            "sip_receive_calls_key";
    private static final String PREF_SIP_LIST = "sip_account_list";
    private static final String TAG = "SipSettings";

    private static final int REQUEST_ADD_OR_EDIT_SIP_PROFILE = 1;

    private PackageManager mPackageManager;
    private SipManager mSipManager;
    private SipProfileDb mProfileDb;

    private SipProfile mProfile; // profile that's being edited

    private CheckBoxPreference mButtonSipReceiveCalls;
    private PreferenceCategory mSipListContainer;
    private Map<String, SipPreference> mSipPreferenceMap;
    private List<SipProfile> mSipProfileList;
    private SipSharedPreferences mSipSharedPreferences;
    private int mUid = Process.myUid();

    private class SipPreference extends Preference {
        SipProfile mProfile;
        SipPreference(Context c, SipProfile p) {
            super(c);
            setProfile(p);
        }

        SipProfile getProfile() {
            return mProfile;
        }

        void setProfile(SipProfile p) {
            mProfile = p;
            setTitle(p.getProfileName());
            updateSummary(mSipSharedPreferences.isReceivingCallsEnabled()
                    ? getString(R.string.registration_status_checking_status)
                    : getString(R.string.registration_status_not_receiving));
        }

        void updateSummary(String registrationStatus) {
            int profileUid = mProfile.getCallingUid();
            boolean isPrimary = mProfile.getUriString().equals(
                    mSipSharedPreferences.getPrimaryAccount());
            Log.v(TAG, "profile uid is " + profileUid + " isPrimary:"
                    + isPrimary + " registration:" + registrationStatus
                    + " Primary:" + mSipSharedPreferences.getPrimaryAccount()
                    + " status:" + registrationStatus);
            String summary = "";
            if ((profileUid > 0) && (profileUid != mUid)) {
                // from third party apps
                summary = getString(R.string.third_party_account_summary,
                        getPackageNameFromUid(profileUid));
            } else if (isPrimary) {
                summary = getString(R.string.primary_account_summary_with,
                        registrationStatus);
            } else {
                summary = registrationStatus;
            }
            setSummary(summary);
        }
    }

    private String getPackageNameFromUid(int uid) {
        try {
            String[] pkgs = mPackageManager.getPackagesForUid(uid);
            ApplicationInfo ai =
                    mPackageManager.getApplicationInfo(pkgs[0], 0);
            return ai.loadLabel(mPackageManager).toString();
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "cannot find name of uid " + uid, e);
        }
        return "uid:" + uid;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mSipManager = SipManager.newInstance(this);
        mSipSharedPreferences = new SipSharedPreferences(this);
        mProfileDb = new SipProfileDb(this);

        mPackageManager = getPackageManager();
        setContentView(R.layout.sip_settings_ui);
        addPreferencesFromResource(R.xml.sip_setting);
        mSipListContainer = (PreferenceCategory) findPreference(PREF_SIP_LIST);
        registerForAddSipListener();
        registerForReceiveCallsCheckBox();

        updateProfilesStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterForContextMenu(getListView());
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
            final Intent intent) {
        if (resultCode != RESULT_OK && resultCode != RESULT_FIRST_USER) return;
        new Thread() {
            public void run() {
            try {
                if (mProfile != null) {
                    Log.v(TAG, "Removed Profile:" + mProfile.getProfileName());
                    deleteProfile(mProfile);
                }

                SipProfile profile = intent.getParcelableExtra(KEY_SIP_PROFILE);
                if (resultCode == RESULT_OK) {
                    Log.v(TAG, "New Profile Name:" + profile.getProfileName());
                    addProfile(profile);
                }
                updateProfilesStatus();
            } catch (IOException e) {
                Log.v(TAG, "Can not handle the profile : " + e.getMessage());
            }
        }}.start();
    }

    private void registerForAddSipListener() {
        ((Button) findViewById(R.id.add_remove_account_button))
                .setOnClickListener(new android.view.View.OnClickListener() {
                    public void onClick(View v) {
                        startSipEditor(null);
                    }
                });
    }

    private void registerForReceiveCallsCheckBox() {
        mButtonSipReceiveCalls = (CheckBoxPreference) findPreference
                (BUTTON_SIP_RECEIVE_CALLS);
        mButtonSipReceiveCalls.setChecked(
                mSipSharedPreferences.isReceivingCallsEnabled());
        mButtonSipReceiveCalls.setOnPreferenceClickListener(
                new OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                        final boolean enabled =
                                ((CheckBoxPreference) preference).isChecked();
                        new Thread(new Runnable() {
                                public void run() {
                                    handleSipReceiveCallsOption(enabled);
                                }
                        }).start();
                        return true;
                    }
                });
    }

    private synchronized void handleSipReceiveCallsOption(boolean enabled) {
        mSipSharedPreferences.setReceivingCallsEnabled(enabled);
        List<SipProfile> sipProfileList = mProfileDb.retrieveSipProfileList();
        for (SipProfile p : sipProfileList) {
            String sipUri = p.getUriString();
            p = updateAutoRegistrationFlag(p, enabled);
            try {
                if (enabled) {
                    mSipManager.open(p,
                            SipUtil.createIncomingCallPendingIntent(), null);
                } else {
                    mSipManager.close(sipUri);
                    if (mSipSharedPreferences.isPrimaryAccount(sipUri)) {
                        // re-open in order to make calls
                        mSipManager.open(p);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "register failed", e);
            }
        }
        updateProfilesStatus();
    }

    private SipProfile updateAutoRegistrationFlag(
            SipProfile p, boolean enabled) {
        SipProfile newProfile = new SipProfile.Builder(p)
                .setAutoRegistration(enabled)
                .build();
        try {
            mProfileDb.deleteProfile(p);
            mProfileDb.saveProfile(newProfile);
        } catch (Exception e) {
            Log.e(TAG, "updateAutoRegistrationFlag error", e);
        }
        return newProfile;
    }

    private void updateProfilesStatus() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    retrieveSipLists();
                } catch (Exception e) {
                    Log.e(TAG, "isRegistered", e);
                }
            }
        }).start();
    }

    private void retrieveSipLists() {
        mSipPreferenceMap = new LinkedHashMap<String, SipPreference>();
        mSipProfileList = mProfileDb.retrieveSipProfileList();
        processActiveProfilesFromSipService();
        Collections.sort(mSipProfileList, new Comparator<SipProfile>() {
            public int compare(SipProfile p1, SipProfile p2) {
                return p1.getProfileName().compareTo(p2.getProfileName());
            }

            public boolean equals(SipProfile p) {
                // not used
                return false;
            }
        });
        mSipListContainer.removeAll();
        for (SipProfile p : mSipProfileList) {
            addPreferenceFor(p);
        }

        if (!mSipSharedPreferences.isReceivingCallsEnabled()) return;
        for (SipProfile p : mSipProfileList) {
            if (mUid == p.getCallingUid()) {
                try {
                    mSipManager.setRegistrationListener(
                            p.getUriString(), createRegistrationListener());
                } catch (SipException e) {
                    Log.e(TAG, "cannot set registration listener", e);
                }
            }
        }
    }

    private void processActiveProfilesFromSipService() {
        SipProfile[] activeList = mSipManager.getListOfProfiles();
        for (SipProfile activeProfile : activeList) {
            SipProfile profile = getProfileFromList(activeProfile);
            if (profile == null) {
                mSipProfileList.add(activeProfile);
            } else {
                profile.setCallingUid(activeProfile.getCallingUid());
            }
        }
    }

    private SipProfile getProfileFromList(SipProfile activeProfile) {
        for (SipProfile p : mSipProfileList) {
            if (p.getUriString().equals(activeProfile.getUriString())) {
                return p;
            }
        }
        return null;
    }

    private void addPreferenceFor(SipProfile p) {
        String status;
        Log.v(TAG, "addPreferenceFor profile uri" + p.getUri());
        SipPreference pref = new SipPreference(this, p);
        mSipPreferenceMap.put(p.getUriString(), pref);
        mSipListContainer.addPreference(pref);

        pref.setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference pref) {
                        handleProfileClick(((SipPreference) pref).mProfile);
                        return true;
                    }
                });
    }

    private void handleProfileClick(final SipProfile profile) {
        int uid = profile.getCallingUid();
        if (uid == mUid || uid == 0) {
            startSipEditor(profile);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.alert_dialog_close)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton(R.string.close_profile,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int w) {
                                deleteProfile(profile);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    void deleteProfile(SipProfile p) {
        mSipProfileList.remove(p);
        SipPreference pref = mSipPreferenceMap.remove(p.getUriString());
        mSipListContainer.removePreference(pref);
    }

    private void addProfile(SipProfile p) throws IOException {
        try {
            mSipManager.setRegistrationListener(p.getUriString(),
                    createRegistrationListener());
        } catch (Exception e) {
            Log.e(TAG, "cannot set registration listener", e);
        }
        mSipProfileList.add(p);
        addPreferenceFor(p);
    }

    private void startSipEditor(final SipProfile profile) {
        mProfile = profile;
        Intent intent = new Intent(this, SipEditor.class);
        intent.putExtra(KEY_SIP_PROFILE, (Parcelable) profile);
        startActivityForResult(intent, REQUEST_ADD_OR_EDIT_SIP_PROFILE);
    }

    private void showRegistrationMessage(final String profileUri,
            final String message) {
        runOnUiThread(new Runnable() {
            public void run() {
                SipPreference pref = mSipPreferenceMap.get(profileUri);
                if (pref != null) {
                    pref.updateSummary(message);
                }
            }
        });
    }

    private SipRegistrationListener createRegistrationListener() {
        return new SipRegistrationListener() {
            public void onRegistrationDone(String profileUri, long expiryTime) {
                showRegistrationMessage(profileUri, getString(
                        R.string.registration_status_done));
            }

            public void onRegistering(String profileUri) {
                showRegistrationMessage(profileUri, getString(
                        R.string.registration_status_registering));
            }

            public void onRegistrationFailed(String profileUri, int errorCode,
                    String message) {
                switch (errorCode) {
                    case SipErrorCode.IN_PROGRESS:
                        showRegistrationMessage(profileUri, getString(
                                R.string.registration_status_still_trying));
                        break;
                    case SipErrorCode.INVALID_CREDENTIALS:
                        showRegistrationMessage(profileUri, getString(
                                R.string.registration_status_invalid_credentials));
                        break;
                    case SipErrorCode.SERVER_UNREACHABLE:
                        showRegistrationMessage(profileUri, getString(
                                R.string.registration_status_server_unreachable));
                        break;
                    case SipErrorCode.DATA_CONNECTION_LOST:
                        showRegistrationMessage(profileUri, getString(
                                R.string.registration_status_no_data));
                        break;
                    case SipErrorCode.CLIENT_ERROR:
                        showRegistrationMessage(profileUri, getString(
                                R.string.registration_status_not_running));
                        break;
                    default:
                        showRegistrationMessage(profileUri, getString(
                                R.string.registration_status_failed_try_later,
                                message));
                }
            }
        };
    }
}
