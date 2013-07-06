package com.fusionx.lightirc.fragments.settings;

import android.os.Bundle;
import android.preference.PreferenceFragment;
import com.fusionx.lightirc.R;

public class MainSettingsFragment extends PreferenceFragment {
    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.main_settings_fragment);
    }
}
