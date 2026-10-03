/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PinMediaTest {
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    public @interface Json { String value(); }

    public static final class Pin {
        @Json("id") String id = "123456";
        @Json("images") Map<String, Image> images = new HashMap<>();
        @Json("videos") Video videos;
        @Json("is_video") Boolean isVideo;
    }

    public static final class Image {
        @Json("url") String url;
        @Json("width") Double width;
        @Json("height") Double height;

        Image(String url, double width, double height) {
            this.url = url; this.width = width; this.height = height;
        }
    }

    public static final class Video {
        @Json("video_list") Map<String, Image> list = new HashMap<>();
    }

    public static final class BoardCover {
        @Json("id") String id = "123456";
        @Json("images") Map<String, Image> images = new HashMap<>();
    }

    @Test public void originalImageIsUsedWithoutGuessingOrPreviewFallback() {
        Pin pin = new Pin();
        pin.images.put("736x", new Image("https://i.pinimg.com/736x/preview.jpg", 736, 736));
        assertNull(PinMedia.source(pin));
        pin.images.put("orig", new Image("https://i.pinimg.com/originals/source.png?token=ok", 3000, 2000));
        assertEquals("https://i.pinimg.com/originals/source.png?token=ok", PinMedia.source(pin).url);
        assertEquals("image/png", PinMedia.source(pin).mime);
        assertEquals(".png", PinMedia.source(pin).suffix);
        assertEquals("https://www.pinterest.com/pin/123456/", PinMedia.pinUrl(pin));
    }

    @Test public void largestPlayableMp4WinsOverPreviewsAndPlaylists() {
        Pin pin = new Pin();
        pin.isVideo = true;
        pin.videos = new Video();
        pin.videos.list.put("small", new Image("https://v.pinimg.com/videos/small.mp4", 640, 360));
        pin.videos.list.put("large", new Image("https://v.pinimg.com/videos/large.mp4", 1920, 1080));
        pin.videos.list.put("hls", new Image("https://v.pinimg.com/videos/master.m3u8", 3840, 2160));
        assertEquals("https://v.pinimg.com/videos/large.mp4", PinMedia.source(pin).url);
        pin.videos.list.clear();
        pin.images.put("orig", new Image("https://i.pinimg.com/originals/thumbnail.jpg", 2000, 2000));
        assertNull(PinMedia.source(pin));
        pin.isVideo = null;
        pin.videos.list.put("hls", new Image("https://v.pinimg.com/videos/master.m3u8", 3840, 2160));
        assertNull(PinMedia.source(pin));
    }

    @Test public void mapModelsAndMissingDimensionAlternativesAreSupported() {
        Map<String, Object> pin = new HashMap<>();
        pin.put("id", "987");
        pin.put("videos", Map.of("video_list", Map.of(
                "first", Map.of("url", "https://v.pinimg.com/a.mp4"),
                "larger", Map.of("url", "https://v.pinimg.com/b.mp4", "width", 1280, "height", 720))));
        assertEquals("https://v.pinimg.com/b.mp4", PinMedia.source(pin).url);
    }

    @Test public void boardsMalformedIdsAndUntrustedMediaAreRefused() {
        assertNull(PinMedia.pinUrl(Map.of("id", "123")));
        assertNull(PinMedia.pinUrl(new BoardCover()));
        assertNull(PinMedia.pinUrl(Map.of("id", "123/../../login", "images", Map.of())));
        for (String url : new String[] {"http://i.pinimg.com/a.jpg", "https://pinimg.com.evil.test/a.jpg",
                "https://i.pinimg.com@evil.test/a.jpg", "https://user@i.pinimg.com/a.jpg", "file:///a.jpg",
                "https://i.pinimg.com:8443/a.jpg", "https://127.0.0.1/a.jpg", "https://i.pinimg.com/a.jpg\n"}) {
            assertNull(url, PinMedia.mediaUri(url));
        }
        assertNotNull(PinMedia.mediaUri("https://I.PINIMG.COM:443/a.jpg"));
        assertTrue(PinMedia.pinterestUri(PinMedia.webUri("https://accounts.pinterest.com/login/")));
        assertTrue(PinMedia.pinterestUri(PinMedia.webUri("https://www.pinterest.co.uk/pin/123/")));
        assertTrue(PinMedia.pinterestUri(PinMedia.webUri("https://pin.it/short")));
        assertFalse(PinMedia.pinterestUri(PinMedia.webUri("https://www.example.com/page")));
    }
}
