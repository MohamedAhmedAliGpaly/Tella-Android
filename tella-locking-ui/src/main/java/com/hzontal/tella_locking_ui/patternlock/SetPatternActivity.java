/*
 * Copyright (c) 2015 Zhang Hai <Dreaming.in.Code.ZH@Gmail.com>
 * All Rights Reserved.
 */

package com.hzontal.tella_locking_ui.patternlock;

import android.os.Bundle;
import android.view.View;


import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.hzontal.tella_locking_ui.R;
import com.hzontal.tella_locking_ui.ui.utils.DialogUtils;

import java.util.ArrayList;
import java.util.List;


/*
 * Part of the source is from platform_packages_apps/Settings
 * android.settings.ChooseLockPattern.java
 */
public class SetPatternActivity extends BasePatternActivity
        implements PatternView.OnPatternListener {

    private enum LeftButtonState {

        Cancel(R.string.pl_cancel, true),
        CancelDisabled(R.string.pl_cancel, false),
        Redraw(R.string.pl_redraw, true),
        RedrawDisabled(R.string.pl_redraw, false);

        public final int textId;
        public final boolean enabled;

        LeftButtonState(int textId, boolean enabled) {
            this.textId = textId;
            this.enabled = enabled;
        }
    }

    private enum RightButtonState {

        Continue(R.string.pl_continue, true),
        ContinueDisabled(R.string.pl_continue, false),
        Confirm(R.string.pl_confirm, true),
        ConfirmDisabled(R.string.pl_confirm, false);

        public final int textId;
        public final boolean enabled;

        RightButtonState(int textId, boolean enabled) {
            this.textId = textId;
            this.enabled = enabled;
        }
    }

    protected enum Stage {

        Draw(R.string.pl_draw_pattern, LeftButtonState.Cancel, RightButtonState.ContinueDisabled,
                true),
        DrawTooShort(R.string.pl_pattern_too_short, LeftButtonState.Redraw,
                RightButtonState.ContinueDisabled, true),
        DrawValid(R.string.pl_pattern_too_short, LeftButtonState.Redraw, RightButtonState.Continue,
                false),
        Confirm(R.string.pl_confirm_pattern, LeftButtonState.Cancel,
                RightButtonState.ConfirmDisabled, true),
        ConfirmWrong(R.string.pl_wrong_pattern, LeftButtonState.Cancel,
                RightButtonState.ConfirmDisabled, true),
        ConfirmCorrect(R.string.pl_pattern_confirmed, LeftButtonState.Cancel,
                RightButtonState.Confirm, false);

        public final int messageId;
        public final LeftButtonState leftButtonState;
        public final RightButtonState rightButtonState;
        public final boolean patternEnabled;

        Stage(int messageId, LeftButtonState leftButtonState, RightButtonState rightButtonState,
              boolean patternEnabled) {
            this.messageId = messageId;
            this.leftButtonState = leftButtonState;
            this.rightButtonState = rightButtonState;
            this.patternEnabled = patternEnabled;
        }
    }

    private static final String KEY_STAGE = "stage";
    private static final String KEY_PATTERN = "pattern";

    private int mMinPatternSize;
    protected List<PatternView.Cell> mPattern;
    protected List<PatternView.Cell> mNewPattern;
    protected Stage mStage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mMinPatternSize = getMinPatternSize();

        mPatternView.setOnPatternListener(this);
        mLeftButton.setOnClickListener(v -> onLeftButtonClicked());
        mRightButton.setOnClickListener(v -> onRightButtonClicked());

        if (savedInstanceState == null) {
            updateStage(Stage.Draw);
        } else {
            String patternString = savedInstanceState.getString(KEY_PATTERN);
            if (patternString != null) {
                mPattern = PatternUtils.stringToPattern(patternString);
            }
            updateStage(Stage.values()[savedInstanceState.getInt(KEY_STAGE)]);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putInt(KEY_STAGE, mStage.ordinal());
        if (mPattern != null) {
            outState.putString(KEY_PATTERN, PatternUtils.patternToString(mPattern));
        }
    }

    @Override
    public void onPatternStart() {

        removeClearPatternRunnable();

       // mMessageText.setText(R.string.pl_recording_pattern);
        mPatternView.setDisplayMode(PatternView.DisplayMode.Correct);
        mLeftButton.setEnabled(false);
        mRightButton.setEnabled(false);
    }

    @Override
    public void onPatternCellAdded(List<PatternView.Cell> pattern) {

    }

    @Override
    public void onPatternDetected(List<PatternView.Cell> newPattern) {
        mNewPattern = newPattern;
        switch (mStage) {
            case Draw:
            case DrawTooShort:
                if (mNewPattern.size() < mMinPatternSize) {
                    updateStage(Stage.DrawTooShort);
                } else {
                    mPattern = new ArrayList<>(mNewPattern);
                    updateStage(Stage.DrawValid);
                }
                break;
            case Confirm:
            case ConfirmWrong:
                if (mNewPattern.equals(mPattern)) {
                    updateStage(Stage.ConfirmCorrect);
                } else {
                    updateStage(Stage.ConfirmWrong);
                }
                break;
            default:
                throw new IllegalStateException("Unexpected stage " + mStage + " when "
                        + "entering the pattern.");
        }
    }

    @Override
    public void onPatternCleared() {
        removeClearPatternRunnable();
    }

    private void onLeftButtonClicked() {
        if (mStage.leftButtonState == LeftButtonState.Redraw) {
                mPattern = null;
                updateStage(Stage.Draw);
        } else if (mStage.leftButtonState == LeftButtonState.Cancel) {
             if (mStage == Stage.ConfirmWrong){
                mNewPattern = null;
                updateStage(Stage.ConfirmWrong);
            }else {
                 onCanceled();
             }

        } else {
            throw new IllegalStateException("left footer button pressed, but stage of " + mStage
                    + " doesn't make sense");
        }
    }

    protected void onCanceled() {
        setResult(RESULT_CANCELED);
        finish();
    }

    private void onRightButtonClicked() {
        if (mStage.rightButtonState == RightButtonState.Continue) {
            if (mStage != Stage.DrawValid) {
                throw new IllegalStateException("expected ui stage " + Stage.DrawValid
                        + " when button is " + RightButtonState.Continue);
            }
            updateStage(Stage.Confirm);
        } else if (mStage.rightButtonState == RightButtonState.Confirm) {
            if (mStage != Stage.ConfirmCorrect) {
                throw new IllegalStateException("expected ui stage " + Stage.ConfirmCorrect
                        + " when button is " + RightButtonState.Confirm);
            }
            onSetPattern(mPattern);
            onConfirmed();
        }
    }

    protected void onConfirmed() {
        setResult(RESULT_OK);
      //  finish();
    }

    protected void updateStage(Stage newStage) {

        Stage previousStage = mStage;
        mStage = newStage;

        if (mStage == Stage.DrawTooShort) {
            mMessageText.setText(getString(mStage.messageId, mMinPatternSize));
        } else if (mStage != Stage.ConfirmWrong){
            mMessageText.setText(mStage.messageId);
        }

        mLeftButton.setText(mStage.leftButtonState.textId);
        mLeftButton.setEnabled(mStage.leftButtonState.enabled);

        mRightButton.setVisibility(mStage == Stage.Confirm ? View.INVISIBLE : View.VISIBLE);
        mRightButton.setText(mStage.rightButtonState.textId);
        mRightButton.setEnabled(mStage.rightButtonState.enabled);
        mRightButton.setTextColor(mStage.rightButtonState.enabled ? ContextCompat.getColor(this,R.color.wa_white) : ContextCompat.getColor(this,R.color.wa_white_40) );

        mPatternView.setInputEnabled(mStage.patternEnabled);

        switch (mStage) {
            case Draw:
            case Confirm:
                // clearPattern() resets display mode to DisplayMode.Correct.
                mPatternView.clearPattern();
                break;
            case DrawTooShort:
                mPatternView.setDisplayMode(PatternView.DisplayMode.Wrong);
                postClearPatternRunnable();
                break;
            case ConfirmWrong:
                DialogUtils.showBottomMessage(this,getString(R.string.pl_incorrect_confirm_pattern),false);
                mPatternView.setDisplayMode(PatternView.DisplayMode.Wrong);
                postClearPatternRunnable();
                break;
            case DrawValid:
            case ConfirmCorrect:
                break;
        }

        // If the stage changed, announce the header for accessibility. This
        // is a no-op when accessibility is disabled.
        if (previousStage != mStage) {
            ViewAccessibilityCompat.announceForAccessibility(mMessageText, mMessageText.getText());
        }
    }

    protected int getMinPatternSize() {
        return 4;
    }

    protected void onSetPattern(List<PatternView.Cell> pattern) {}
}
