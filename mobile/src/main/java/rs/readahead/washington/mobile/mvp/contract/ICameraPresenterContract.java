package rs.readahead.washington.mobile.mvp.contract;

import android.content.Context;

import java.io.File;

import rs.readahead.washington.mobile.domain.entity.MediaFile;
import rs.readahead.washington.mobile.media.MediaFileBundle;


public class ICameraPresenterContract {
    public interface IView {
        void onAddingStart();
        void onAddingEnd();
        void onAddSuccess(MediaFileBundle bundle);
        void onAddError(Throwable error);
        void rotateViews(int rotation);
        void onLastMediaFileSuccess(MediaFile mediaFile);
        void onLastMediaFileError(Throwable throwable);
        Context getContext();
    }

    public interface IPresenter extends IBasePresenter {
        void addJpegPhoto(byte[] jpeg);
        void addMp4Video(File file);
        void handleRotation(int orientation);
        void getLastMediaFile();
    }
}
