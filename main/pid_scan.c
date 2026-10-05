#include "pid_scan.h"

#include <stdio.h>
#include <string.h>

#define LINE_MAX 160
#define PIDS_PER_LIST_LINE 20
#define BYTES_PER_LINE 4

void pid_scan_init(pid_scan_t *scan, const pid_scan_config_t *config, uint32_t now_ms)
{
    memset(scan, 0, sizeof(*scan));
    scan->config = *config;
    scan->asking = -1;
    scan->start_ms = now_ms;
}

static void bytes_update(pid_scan_bytes_t *b, bool first_sample, const uint8_t *data, uint8_t len)
{
    if (len > sizeof(b->last)) len = sizeof(b->last);
    if (first_sample) {
        b->len = len;
        memcpy(b->last, data, len);
        memcpy(b->min, data, len);
        memcpy(b->max, data, len);
        return;
    }
    /* A reply that suddenly carries more bytes starts those bytes fresh. */
    for (uint8_t i = b->len; i < len; ++i) {
        b->last[i] = b->min[i] = b->max[i] = data[i];
    }
    if (len > b->len) b->len = len;
    for (uint8_t i = 0; i < len; ++i) {
        if (data[i] != b->last[i] && b->changes[i] < UINT16_MAX) ++b->changes[i];
        if (data[i] < b->min[i]) b->min[i] = data[i];
        if (data[i] > b->max[i]) b->max[i] = data[i];
        b->last[i] = data[i];
    }
}

void pid_scan_on_ask(pid_scan_t *scan, uint8_t pid)
{
    scan->asking = pid;
    scan->negative_this_ask = false;
    if (scan->pids[pid].asks < UINT16_MAX) ++scan->pids[pid].asks;
}

void pid_scan_on_done(pid_scan_t *scan, uint8_t pid, bool answered)
{
    pid_scan_pid_t *p = &scan->pids[pid];
    if (!answered && !(scan->asking == pid && scan->negative_this_ask) && p->silent < UINT16_MAX) {
        ++p->silent;
    }
    if (scan->asking == pid) scan->asking = -1;
}

static void record_bitmap(pid_scan_t *scan, uint8_t base, const uint8_t *value, uint8_t len)
{
    if (len < 4) return;
    scan->bitmap_answered[base >> 5] = true;
    for (int i = 0; i < 32; ++i) {
        if ((value[i >> 3] >> (7 - (i & 7))) & 1) {
            int pid = base + 1 + i;
            if (pid <= 0xFF) scan->claimed[pid >> 3] |= (uint8_t)(0x80 >> (pid & 7));
        }
    }
}

static void record_obd_reply(pid_scan_t *scan, uint32_t id, const uint8_t *data, uint8_t len)
{
    if (len < 3 || (data[0] & 0xF0) != 0) return; /* ISO-TP single frames only */
    uint8_t payload = data[0] & 0x0F;
    if (payload == 0 || payload > len - 1) payload = len - 1;

    if (data[1] == 0x7F && data[2] == 0x01) {
        /* Mode 01 negative responses don't name the PID; only one request
         * is on the bus at a time, so it belongs to that one. */
        if (scan->asking >= 0) {
            pid_scan_pid_t *p = &scan->pids[scan->asking];
            if (p->negative < UINT16_MAX) ++p->negative;
            p->last_nrc = payload >= 3 ? data[3] : 0;
            scan->negative_this_ask = true;
        }
        return;
    }
    if (data[1] != 0x41 || payload < 2) return;

    uint8_t pid = data[2];
    const uint8_t *value = &data[3];
    uint8_t value_len = payload - 2;
    if (value_len > PID_SCAN_MAX_VALUE_BYTES) value_len = PID_SCAN_MAX_VALUE_BYTES;

    pid_scan_pid_t *p = &scan->pids[pid];
    bytes_update(&p->bytes, p->replies == 0, value, value_len);
    if (p->replies < UINT16_MAX) ++p->replies;
    uint32_t slot = id - scan->config.response_id_min;
    if (slot < 8) p->responders |= (uint8_t)(1u << slot);
    if ((pid & 0x1F) == 0 && pid <= 0xE0) record_bitmap(scan, pid, value, value_len);
}

static pid_scan_id_t *find_id(pid_scan_t *scan, uint32_t id, bool extended)
{
    for (uint16_t i = 0; i < scan->id_count; ++i) {
        if (scan->ids[i].id == id && scan->ids[i].extended == extended) return &scan->ids[i];
    }
    if (scan->id_count >= PID_SCAN_MAX_IDS) return NULL;
    pid_scan_id_t *entry = &scan->ids[scan->id_count++];
    memset(entry, 0, sizeof(*entry));
    entry->id = id;
    entry->extended = extended;
    return entry;
}

void pid_scan_on_frame(pid_scan_t *scan, uint32_t id, bool extended, const uint8_t *data,
                       uint8_t len, uint32_t now_ms)
{
    if (len > 8) len = 8;
    const pid_scan_config_t *cfg = &scan->config;
    if (!extended && id >= cfg->response_id_min && id <= cfg->response_id_max) {
        record_obd_reply(scan, id, data, len);
        return;
    }
    if (!extended && id == cfg->request_id) return; /* another tester's request */

    pid_scan_id_t *entry = find_id(scan, id, extended);
    if (entry == NULL) {
        ++scan->ids_overflow;
        return;
    }
    if (entry->count == 0) entry->first_ms = now_ms;
    bytes_update(&entry->bytes, entry->count == 0, data, len);
    entry->last_ms = now_ms;
    ++entry->count;
}

bool pid_scan_pid_answered(const pid_scan_t *scan, uint8_t pid)
{
    return scan->pids[pid].replies > 0;
}

bool pid_scan_pid_claimed(const pid_scan_t *scan, uint8_t pid)
{
    if ((pid & 0x1F) == 0) {
        /* A bitmap PID is claimed by the bitmap before it (0x00 always). */
        return pid == 0 || ((scan->claimed[pid >> 3] >> (7 - (pid & 7))) & 1);
    }
    return (scan->claimed[pid >> 3] >> (7 - (pid & 7))) & 1;
}

const char *pid_scan_pid_name(uint8_t pid)
{
    switch (pid) {
    case 0x01: return "STATUS";
    case 0x03: return "FUELSYS";
    case 0x04: return "LOAD";
    case 0x05: return "COOLANT";
    case 0x06: return "STFT1";
    case 0x07: return "LTFT1";
    case 0x0A: return "FUELPRS";
    case 0x0B: return "MAP";
    case 0x0C: return "RPM";
    case 0x0D: return "SPEED";
    case 0x0E: return "TIMING";
    case 0x0F: return "IAT";
    case 0x10: return "MAF";
    case 0x11: return "THROTTL";
    case 0x13: return "O2LOC";
    case 0x14: return "O2-B1S1";
    case 0x15: return "O2-B1S2";
    case 0x1C: return "OBDSTD";
    case 0x1F: return "RUNTIME";
    case 0x21: return "DISTMIL";
    case 0x2C: return "EGRCMD";
    case 0x2E: return "EVAP";
    case 0x2F: return "FUELLVL";
    case 0x30: return "WARMUPS";
    case 0x31: return "DISTCLR";
    case 0x33: return "BARO";
    case 0x3C: return "CATTEMP";
    case 0x41: return "MONSTAT";
    case 0x42: return "VOLTS";
    case 0x43: return "ABSLOAD";
    case 0x44: return "LAMBDA";
    case 0x45: return "RELTHR";
    case 0x46: return "AMBIENT";
    case 0x47: return "THR-B";
    case 0x49: return "PEDAL-D";
    case 0x4C: return "THRCMD";
    case 0x4D: return "TIMEMIL";
    case 0x4E: return "TIMECLR";
    case 0x51: return "FUELTYP";
    case 0x5A: return "RELPEDL";
    case 0x5C: return "OILTEMP";
    case 0x5E: return "FUELRAT";
    case 0x5F: return "EMISREQ";
    case 0x8E: return "FRICTRQ";
    case 0xA6: return "ODOMETR";
    default: return NULL;
    }
}

/* Standard decoding of the latest reply, for the PIDs where it helps to
 * see a real-world value (e.g. "0 km/h" while riding). */
static void decode_hint(uint8_t pid, const pid_scan_bytes_t *b, char *out, size_t size)
{
    out[0] = '\0';
    if (b->len == 0) return;
    unsigned a = b->last[0];
    unsigned ab = b->len > 1 ? (a << 8) | b->last[1] : a;
    switch (pid) {
    case 0x04: case 0x11: case 0x2F: case 0x45: case 0x47: case 0x49: case 0x4C: case 0x5A:
        snprintf(out, size, "= %u %%", a * 100 / 255); break;
    case 0x05: case 0x0F: case 0x46: case 0x5C:
        snprintf(out, size, "= %d C", (int)a - 40); break;
    case 0x06: case 0x07:
        snprintf(out, size, "= %d %%", ((int)a - 128) * 100 / 128); break;
    case 0x0B: case 0x33: snprintf(out, size, "= %u kPa", a); break;
    case 0x0C: if (b->len > 1) snprintf(out, size, "= %u rpm", ab / 4); break;
    case 0x0D: snprintf(out, size, "= %u km/h", a); break;
    case 0x0E: snprintf(out, size, "= %d deg", (int)a / 2 - 64); break;
    case 0x10: if (b->len > 1) snprintf(out, size, "= %u.%02u g/s", ab / 100, ab % 100); break;
    case 0x14: case 0x15: snprintf(out, size, "= %u mV", a * 5); break;
    case 0x1F: case 0x4D: case 0x4E: if (b->len > 1) snprintf(out, size, "= %u", ab); break;
    case 0x21: case 0x31: if (b->len > 1) snprintf(out, size, "= %u km", ab); break;
    case 0x42: if (b->len > 1) snprintf(out, size, "= %u.%03u V", ab / 1000, ab % 1000); break;
    case 0x5E: if (b->len > 1) snprintf(out, size, "= %u.%02u L/h", ab / 20, (ab % 20) * 5); break;
    case 0xA6:
        if (b->len >= 4) {
            uint32_t odo = ((uint32_t)b->last[0] << 24) | ((uint32_t)b->last[1] << 16) |
                           ((uint32_t)b->last[2] << 8) | b->last[3];
            snprintf(out, size, "= %lu.%lu km", (unsigned long)(odo / 10), (unsigned long)(odo % 10));
        }
        break;
    default: break;
    }
}

/* "B0 0B[08-2A x40]": byte, latest value, [min-max xchanges]. */
static int append_bytes(char *line, int used, const pid_scan_bytes_t *b, uint8_t from, uint8_t to)
{
    for (uint8_t i = from; i < to && i < b->len && used < LINE_MAX - 20; ++i) {
        if (b->changes[i] == 0) {
            used += snprintf(line + used, LINE_MAX - (size_t)used, " B%u %02X[fixed]", i, b->last[i]);
        } else {
            used += snprintf(line + used, LINE_MAX - (size_t)used, " B%u %02X[%02X-%02X x%u]", i,
                             b->last[i], b->min[i], b->max[i], b->changes[i]);
        }
    }
    return used;
}

typedef bool (*pid_filter_fn)(const pid_scan_t *scan, uint8_t pid);

static bool f_answered(const pid_scan_t *s, uint8_t pid)
{
    return (pid & 0x1F) != 0 && pid_scan_pid_answered(s, pid);
}
static bool f_claimed_silent(const pid_scan_t *s, uint8_t pid)
{
    return (pid & 0x1F) != 0 && pid_scan_pid_claimed(s, pid) && !pid_scan_pid_answered(s, pid) &&
           s->pids[pid].asks > 0;
}
static bool f_unclaimed_answered(const pid_scan_t *s, uint8_t pid)
{
    return (pid & 0x1F) != 0 && pid_scan_pid_answered(s, pid) && !pid_scan_pid_claimed(s, pid);
}
static bool f_negative(const pid_scan_t *s, uint8_t pid)
{
    return s->pids[pid].negative > 0;
}
static bool f_bitmap(const pid_scan_t *s, uint8_t pid)
{
    return (pid & 0x1F) == 0 && pid <= 0xE0 && s->bitmap_answered[pid >> 5];
}

static void report_list(const pid_scan_t *scan, const char *title, pid_filter_fn filter,
                        bool with_nrc, pid_scan_line_fn out, void *ctx)
{
    unsigned total = 0;
    for (int pid = 0; pid <= 0xFF; ++pid) {
        if (filter(scan, (uint8_t)pid)) ++total;
    }
    char line[LINE_MAX];
    int used = snprintf(line, sizeof(line), "%s (%u):%s", title, total, total ? "" : " -");
    unsigned on_line = 0;
    for (int pid = 0; pid <= 0xFF; ++pid) {
        if (!filter(scan, (uint8_t)pid)) continue;
        if (on_line == PIDS_PER_LIST_LINE) {
            out(line, ctx);
            used = snprintf(line, sizeof(line), "   ");
            on_line = 0;
        }
        if (with_nrc) {
            used += snprintf(line + used, sizeof(line) - (size_t)used, " %02X(NRC %02X)", pid,
                             scan->pids[pid].last_nrc);
            on_line += 2;
        } else {
            used += snprintf(line + used, sizeof(line) - (size_t)used, " %02X", pid);
            ++on_line;
        }
    }
    out(line, ctx);
}

void pid_scan_report(const pid_scan_t *scan, uint32_t now_ms, pid_scan_line_fn out, void *ctx)
{
    char line[LINE_MAX];
    snprintf(line, sizeof(line), "==== PID SCAN REPORT  t=%lu s  sweeps=%lu  watch=%lu  lost=%lu ====",
             (unsigned long)((now_ms - scan->start_ms) / 1000), (unsigned long)scan->sweeps,
             (unsigned long)scan->watch_loops,
             (unsigned long)(scan->frames_dropped + scan->ids_overflow));
    out(line, ctx);

    report_list(scan, "Bitmaps answered", f_bitmap, false, out, ctx);
    report_list(scan, "Answered", f_answered, false, out, ctx);
    report_list(scan, "Claimed but never answered", f_claimed_silent, false, out, ctx);
    report_list(scan, "Answered but not claimed", f_unclaimed_answered, false, out, ctx);
    report_list(scan, "Negative response", f_negative, true, out, ctx);

    out("-- Answering PIDs: ECU, claimed, replies/asks, bytes now[min-max xchanges] --", ctx);
    for (int pid = 0; pid <= 0xFF; ++pid) {
        if (!f_answered(scan, (uint8_t)pid)) continue;
        const pid_scan_pid_t *p = &scan->pids[pid];
        const char *name = pid_scan_pid_name((uint8_t)pid);
        char ecus[24] = "";
        int e = 0;
        for (int i = 0; i < 8; ++i) {
            if (p->responders & (1 << i)) {
                e += snprintf(ecus + e, sizeof(ecus) - (size_t)e, "%s%03lX", e ? "+" : "",
                              (unsigned long)(scan->config.response_id_min + (uint32_t)i));
            }
        }
        char hint[24];
        decode_hint((uint8_t)pid, &p->bytes, hint, sizeof(hint));
        int used = snprintf(line, sizeof(line), "PID %02X %-7s %s %s %u/%u", pid,
                            name ? name : "?", ecus, pid_scan_pid_claimed(scan, (uint8_t)pid) ? "Y" : "N",
                            p->replies, p->asks);
        used = append_bytes(line, used, &p->bytes, 0, PID_SCAN_MAX_VALUE_BYTES);
        if (hint[0] && used < LINE_MAX - 2) {
            snprintf(line + used, sizeof(line) - (size_t)used, "  %s", hint);
        }
        out(line, ctx);
    }

    snprintf(line, sizeof(line), "-- Other CAN traffic: %u IDs (not OBD replies) --",
             (unsigned)scan->id_count);
    out(line, ctx);
    /* Ascending ID order without sorting the table in place. */
    uint64_t last_key = 0;
    bool first = true;
    for (uint16_t n = 0; n < scan->id_count; ++n) {
        const pid_scan_id_t *best = NULL;
        uint64_t best_key = 0;
        for (uint16_t i = 0; i < scan->id_count; ++i) {
            const pid_scan_id_t *c = &scan->ids[i];
            uint64_t key = ((uint64_t)c->extended << 32) | c->id;
            if ((first || key > last_key) && (best == NULL || key < best_key)) {
                best = c;
                best_key = key;
            }
        }
        if (best == NULL) break;
        first = false;
        last_key = best_key;

        unsigned period = best->count > 1
                              ? (unsigned)((best->last_ms - best->first_ms) / (best->count - 1)) : 0;
        int used = snprintf(line, sizeof(line), "ID %0*lX n=%lu every %u ms", best->extended ? 8 : 3,
                            (unsigned long)best->id, (unsigned long)best->count, period);
        used = append_bytes(line, used, &best->bytes, 0, BYTES_PER_LINE);
        out(line, ctx);
        if (best->bytes.len > BYTES_PER_LINE) {
            used = snprintf(line, sizeof(line), "   ");
            append_bytes(line, used, &best->bytes, BYTES_PER_LINE, 8);
            out(line, ctx);
        }
    }
    out("==== END ====", ctx);
}
