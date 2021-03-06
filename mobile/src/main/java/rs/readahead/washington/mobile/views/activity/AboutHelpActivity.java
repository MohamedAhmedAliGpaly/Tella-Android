package rs.readahead.washington.mobile.views.activity;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import rs.readahead.washington.mobile.BuildConfig;
import rs.readahead.washington.mobile.R;
import rs.readahead.washington.mobile.util.Util;


public class AboutHelpActivity extends CacheWordSubscriberBaseActivity {
    @BindView(R.id.version)
    TextView version;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about_help);

        ButterKnife.bind(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();

        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.settings_about_app_bar);
        }

        version.setText(String.format("%s %s", getString(R.string.settings_about_app_version), BuildConfig.VERSION_NAME));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @OnClick({R.id.setup, R.id.faq, R.id.contact, R.id.privacy})
    public void startActivity(View view) {
        String url = null;

        switch (view.getId()) {
            case R.id.setup:
                Intent intent = new Intent(this, TellaIntroActivity.class);
                intent.putExtra(TellaIntroActivity.FROM_ABOUT, true);
                startActivity(intent);
                return;

            case R.id.faq:
                url = getString(R.string.config_faq_url);
                break;

            case R.id.contact:
                url = getString(R.string.config_contact_url);
                break;

             case R.id.privacy:
                url = getString(R.string.config_privacy_url);
                break;
        }

        if (url != null) {
            Util.startBrowserIntent(this, url);
        }
    }
}
