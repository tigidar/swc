/*
 * Launcher widget rendering for SWC compositor.
 *
 * Provides:
 * - Custom wlr_buffer implementation for raw ARGB8888 pixel data
 * - Bitmap font text rendering using Spleen 16x32
 * - Scene buffer helpers for wlr_scene integration
 * - XKB keysym-to-UTF32 conversion
 */

#define _POSIX_C_SOURCE 199309L
#define WLR_USE_UNSTABLE

#include <wayland-server-core.h>
#include <wlr/interfaces/wlr_buffer.h>
#include <wlr/types/wlr_scene.h>
#include <xkbcommon/xkbcommon.h>
#include <drm_fourcc.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "spleen_16x32.h"

/* ── Custom wlr_buffer for raw ARGB8888 pixel data ──────────────── */

struct launcher_buffer {
    struct wlr_buffer base;
    uint8_t *data;
    uint32_t stride;
};

static void lb_destroy(struct wlr_buffer *wlr_buf) {
    struct launcher_buffer *buf = wl_container_of(wlr_buf, buf, base);
    free(buf->data);
    free(buf);
}

static bool lb_begin_data_ptr_access(struct wlr_buffer *wlr_buf,
        uint32_t flags, void **data, uint32_t *format, size_t *stride) {
    struct launcher_buffer *buf = wl_container_of(wlr_buf, buf, base);
    *data = buf->data;
    *format = DRM_FORMAT_ARGB8888;
    *stride = buf->stride;
    return true;
}

static void lb_end_data_ptr_access(struct wlr_buffer *wlr_buf) {
    /* no-op */
}

static const struct wlr_buffer_impl launcher_buffer_impl = {
    .destroy = lb_destroy,
    .begin_data_ptr_access = lb_begin_data_ptr_access,
    .end_data_ptr_access = lb_end_data_ptr_access,
};

/* ── Buffer management ──────────────────────────────────────────── */

struct wlr_buffer *helper_launcher_buffer_create(int width, int height) {
    struct launcher_buffer *buf = calloc(1, sizeof(*buf));
    if (!buf) return NULL;
    buf->stride = (uint32_t)width * 4;
    buf->data = calloc((size_t)width * (size_t)height, 4);
    if (!buf->data) {
        free(buf);
        return NULL;
    }
    wlr_buffer_init(&buf->base, &launcher_buffer_impl, width, height);
    return &buf->base;
}

uint8_t *helper_launcher_buffer_get_data(struct wlr_buffer *wlr_buf) {
    struct launcher_buffer *buf = wl_container_of(wlr_buf, buf, base);
    return buf->data;
}

/* ── Text rendering ─────────────────────────────────────────────── */

void helper_launcher_render(struct wlr_buffer *wlr_buf,
        const char *text, int text_len,
        int buf_width, int buf_height) {
    struct launcher_buffer *buf = wl_container_of(wlr_buf, buf, base);
    uint32_t *pixels = (uint32_t *)buf->data;

    /* Colors */
    uint32_t bg_color     = 0xF0202030;  /* near-black, slight alpha */
    uint32_t text_color   = 0xFFE0E0E0;  /* light gray */
    uint32_t border_color = 0xFF404060;  /* subtle border */
    uint32_t cursor_color = 0xFFA0A0C0;  /* cursor line */
    uint32_t prompt_color = 0xFF808090;  /* prompt ">" dimmer */

    /* Fill background */
    for (int i = 0; i < buf_width * buf_height; i++)
        pixels[i] = bg_color;

    /* Draw 2px border */
    for (int x = 0; x < buf_width; x++) {
        pixels[x] = border_color;
        pixels[buf_width + x] = border_color;
        pixels[(buf_height - 1) * buf_width + x] = border_color;
        pixels[(buf_height - 2) * buf_width + x] = border_color;
    }
    for (int y = 0; y < buf_height; y++) {
        pixels[y * buf_width] = border_color;
        pixels[y * buf_width + 1] = border_color;
        pixels[y * buf_width + buf_width - 1] = border_color;
        pixels[y * buf_width + buf_width - 2] = border_color;
    }

    int pad_x = SPLEEN_GLYPH_WIDTH;  /* 16px left padding */
    int pad_y = (buf_height - SPLEEN_GLYPH_HEIGHT) / 2;

    /* Draw ">" prompt character */
    {
        unsigned char ch = '>';
        int glyph_idx = ch - SPLEEN_FIRST_CHAR;
        for (int row = 0; row < SPLEEN_GLYPH_HEIGHT; row++) {
            uint16_t bits = spleen_glyphs[glyph_idx][row];
            for (int col = 0; col < SPLEEN_GLYPH_WIDTH; col++) {
                if (bits & (0x8000 >> col)) {
                    int px = pad_x + col;
                    int py = pad_y + row;
                    if (px >= 0 && px < buf_width && py >= 0 && py < buf_height)
                        pixels[py * buf_width + px] = prompt_color;
                }
            }
        }
    }

    /* Offset text after prompt + small gap */
    int text_start_x = pad_x + SPLEEN_GLYPH_WIDTH + (SPLEEN_GLYPH_WIDTH / 2);

    /* Render text glyphs */
    for (int i = 0; i < text_len; i++) {
        unsigned char ch = (unsigned char)text[i];
        if (ch < SPLEEN_FIRST_CHAR || ch > SPLEEN_LAST_CHAR) continue;
        int glyph_idx = ch - SPLEEN_FIRST_CHAR;

        int gx = text_start_x + i * SPLEEN_GLYPH_WIDTH;
        if (gx + SPLEEN_GLYPH_WIDTH > buf_width - pad_x) break;  /* clip */

        for (int row = 0; row < SPLEEN_GLYPH_HEIGHT; row++) {
            uint16_t bits = spleen_glyphs[glyph_idx][row];
            for (int col = 0; col < SPLEEN_GLYPH_WIDTH; col++) {
                if (bits & (0x8000 >> col)) {
                    int px = gx + col;
                    int py = pad_y + row;
                    if (px >= 0 && px < buf_width && py >= 0 && py < buf_height)
                        pixels[py * buf_width + px] = text_color;
                }
            }
        }
    }

    /* Draw cursor (thin vertical line after last char) */
    int cursor_x = text_start_x + text_len * SPLEEN_GLYPH_WIDTH + 2;
    if (cursor_x + 2 < buf_width - pad_x) {
        for (int y = pad_y + 4; y < pad_y + SPLEEN_GLYPH_HEIGHT - 4; y++) {
            if (y >= 0 && y < buf_height) {
                pixels[y * buf_width + cursor_x] = cursor_color;
                pixels[y * buf_width + cursor_x + 1] = cursor_color;
            }
        }
    }
}

