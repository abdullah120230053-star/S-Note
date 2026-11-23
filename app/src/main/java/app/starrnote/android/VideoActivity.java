package app.starrnote.android;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.VideoView;

public final class VideoActivity extends Activity {
    private LinearLayout controls;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        StudioStyle style = new StudioStyle(this, 0);
        LinearLayout body = style.column();
        body.setBackgroundColor(0xff102522);
        controls = style.row();
        style.pad(controls, 12, 8, 12, 8);
        controls.setGravity(Gravity.CENTER);
        style.chip(controls, style.button("Close", false, this::finish));
        style.chip(controls, style.button("Picture in picture", true, this::minimize));
        body.addView(controls);
        VideoView video = new VideoView(this);
        body.addView(video, new LinearLayout.LayoutParams(-1, 0, 1));
        MediaController controller = new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setVideoURI(uri);
        setContentView(body);
        video.start();
    }

    private void minimize() {
        if (getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
        }
    }

    @Override public void onPictureInPictureModeChanged(boolean active, android.content.res.Configuration config) {
        super.onPictureInPictureModeChanged(active, config);
        controls.setVisibility(active ? android.view.View.GONE : android.view.View.VISIBLE);
    }
}
