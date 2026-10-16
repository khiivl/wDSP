package com.qf.musicplayer.bean;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * The platform's now-playing parcel, redeclared here so we can unparcel it.
 *
 * <h2>Why a copy, and why in this package</h2>
 *
 * The built-in music player broadcasts {@code com.qf.musicplayer.action.UPDATE_ACTION} with one of
 * these as an extra, and the launcher's own widget reads it. Unparcelling resolves the class by
 * name through the receiving app's classloader, so a declaration with the same package, the same
 * class name and the same field order in {@code writeToParcel} is enough - there is no way to ask
 * the platform for the original, and reading the raw parcel by hand would be the same field order
 * written less legibly.
 *
 * <p>🔬 Copied field for field from the launcher's decompiled copy
 * ({@code com/qf/musicplayer/bean/MusicInfoData}). <b>The order below is the wire format.</b>
 * Changing it does not produce an error, it produces plausible nonsense - a title that is really
 * an artist, a duration that is really a track number.
 */
public class MusicInfoData implements Parcelable {

    public static final Creator<MusicInfoData> CREATOR = new Creator<MusicInfoData>() {
        @Override
        public MusicInfoData createFromParcel(Parcel parcel) {
            MusicInfoData data = new MusicInfoData();
            data.name = parcel.readString();
            data.artist = parcel.readString();
            data.path = parcel.readString();
            data.currTime = parcel.readInt();
            data.totalTime = parcel.readInt();
            data.curPlayStatus = parcel.readInt();
            data.index = parcel.readInt();
            data.listSize = parcel.readInt();
            data.parentFilePath = parcel.readString();
            data.parentFileName = parcel.readString();
            data.favourite = parcel.readInt();
            data.album = parcel.readString();
            return data;
        }

        @Override
        public MusicInfoData[] newArray(int size) {
            return new MusicInfoData[size];
        }
    };

    private String name;
    private String artist;
    private String path;
    private int currTime;
    private int totalTime;
    private int curPlayStatus;
    private int index;
    private int listSize;
    private String parentFilePath;
    private String parentFileName;
    private int favourite;
    private String album;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(name);
        parcel.writeString(artist);
        parcel.writeString(path);
        parcel.writeInt(currTime);
        parcel.writeInt(totalTime);
        parcel.writeInt(curPlayStatus);
        parcel.writeInt(index);
        parcel.writeInt(listSize);
        parcel.writeString(parentFilePath);
        parcel.writeString(parentFileName);
        parcel.writeInt(favourite);
        parcel.writeString(album);
    }

    public String getName() {
        return name;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    /** The file, which is also where the embedded cover art has to be read from. */
    public String getPath() {
        return path;
    }

    public int getCurrTime() {
        return currTime;
    }

    public int getTotalTime() {
        return totalTime;
    }

    public int getCurPlayStatus() {
        return curPlayStatus;
    }
}
