package com.xuewu.bvds;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 使用 Android MediaExtractor + MediaMuxer 合并视频和音频轨道。
 */
public class MediaMerger {

    public boolean merge(String videoPath, String audioPath, String outputPath) throws IOException {
        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        MediaMuxer muxer = null;

        try {
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(videoPath);

            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(audioPath);

            int videoTrackIndex = -1;
            MediaFormat videoFormat = null;
            for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
                MediaFormat fmt = videoExtractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    videoFormat = fmt;
                    break;
                }
            }

            int audioTrackIndex = -1;
            MediaFormat audioFormat = null;
            for (int i = 0; i < audioExtractor.getTrackCount(); i++) {
                MediaFormat fmt = audioExtractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    audioFormat = fmt;
                    break;
                }
            }

            if (videoTrackIndex < 0) throw new IOException("未找到视频轨道");
            if (audioTrackIndex < 0) throw new IOException("未找到音频轨道");

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            videoExtractor.selectTrack(videoTrackIndex);
            audioExtractor.selectTrack(audioTrackIndex);

            int muxerVideoTrack = muxer.addTrack(videoFormat);
            int muxerAudioTrack = muxer.addTrack(audioFormat);

            muxer.start();

            writeTrack(videoExtractor, muxer, muxerVideoTrack);
            writeTrack(audioExtractor, muxer, muxerAudioTrack);

            muxer.stop();
            return true;

        } finally {
            if (muxer != null) { try { muxer.release(); } catch (Exception ignored) {} }
            if (videoExtractor != null) { try { videoExtractor.release(); } catch (Exception ignored) {} }
            if (audioExtractor != null) { try { audioExtractor.release(); } catch (Exception ignored) {} }
        }
    }

    private void writeTrack(MediaExtractor extractor, MediaMuxer muxer, int trackIndex) {
        ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (true) {
            bufferInfo.offset = 0;
            bufferInfo.size = extractor.readSampleData(buffer, 0);
            if (bufferInfo.size < 0) break;

            bufferInfo.presentationTimeUs = extractor.getSampleTime();
            bufferInfo.flags = extractor.getSampleFlags();

            muxer.writeSampleData(trackIndex, buffer, bufferInfo);
            extractor.advance();
        }
    }
}
