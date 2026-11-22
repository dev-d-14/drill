package app.sthira;

import android.content.Context;
import android.health.connect.AggregateRecordsGroupedByPeriodResponse;
import android.health.connect.AggregateRecordsRequest;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.InsertRecordsResponse;
import android.health.connect.LocalTimeRangeFilter;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.SleepSessionRecord;
import android.health.connect.datatypes.StepsRecord;
import android.os.OutcomeReceiver;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Health Connect through the platform API (Android 14+), so the app needs no library.
 * Only call these after checking Build.VERSION.SDK_INT >= 34.
 */
final class HealthSteps {

    static final String HC_WRITE_SLEEP = "android.permission.health.WRITE_SLEEP";
    private static final Executor IO = Executors.newSingleThreadExecutor();

    interface Week {
        void done(long[] week, boolean ok);
    }

    private HealthSteps() {
    }

    private static HealthConnectManager manager(Context c) {
        return c.getSystemService(HealthConnectManager.class);
    }

    /** Steps per day for the last seven days, today last, as Health Connect totals them across sources. */
    static void week(Context c, Week r) {
        long[] out = new long[7];
        try {
            HealthConnectManager hc = manager(c);
            if (hc == null) {
                r.done(out, false);
                return;
            }
            LocalDate today = LocalDate.now();
            LocalTimeRangeFilter range = new LocalTimeRangeFilter.Builder()
                    .setStartTime(today.minusDays(6).atStartOfDay())
                    .setEndTime(today.plusDays(1).atStartOfDay())
                    .build();
            AggregateRecordsRequest<Long> req = new AggregateRecordsRequest.Builder<Long>(range)
                    .addAggregationType(StepsRecord.STEPS_COUNT_TOTAL)
                    .build();
            hc.aggregateGroupByPeriod(req, Period.ofDays(1), IO,
                    new OutcomeReceiver<List<AggregateRecordsGroupedByPeriodResponse<Long>>, HealthConnectException>() {
                        @Override
                        public void onResult(List<AggregateRecordsGroupedByPeriodResponse<Long>> list) {
                            for (AggregateRecordsGroupedByPeriodResponse<Long> g : list) {
                                LocalDate d = g.getStartTime().toLocalDate();
                                int i = 6 - (int) (today.toEpochDay() - d.toEpochDay());
                                Long v = g.get(StepsRecord.STEPS_COUNT_TOTAL);
                                if (i >= 0 && i < 7 && v != null) out[i] = v;
                            }
                            r.done(out, true);
                        }

                        @Override
                        public void onError(HealthConnectException e) {
                            r.done(out, false);
                        }
                    });
        } catch (Throwable t) {
            r.done(out, false);
        }
    }

    // ---- sleep ----

    static boolean canWriteSleep(Context c) {
        return c.checkSelfPermission(HC_WRITE_SLEEP) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Writes one night as one sleep session with its stages inside it.
     * stages: one char per 30-second epoch from startMs: W wake, L light, D deep, R REM.
     */
    static void writeSleep(Context c, long id, long startMs, long endMs, String stages, boolean withRem) {
        if (!canWriteSleep(c)) return;
        try {
            HealthConnectManager hc = manager(c);
            if (hc == null) return;
            ZoneOffset off = ZoneId.systemDefault().getRules().getOffset(Instant.ofEpochMilli(startMs));
            List<SleepSessionRecord.Stage> list = new ArrayList<>();
            int n = stages.length();
            int i = 0;
            while (i < n) {
                char s = stages.charAt(i);
                int j = i;
                while (j < n && stages.charAt(j) == s) j++;
                long a = startMs + i * 30_000L, b = Math.min(endMs, startMs + j * 30_000L);
                if (b > a) list.add(new SleepSessionRecord.Stage(Instant.ofEpochMilli(a), Instant.ofEpochMilli(b), type(s, withRem)));
                i = j;
            }
            // The version goes up with the staging model, so a re-staged night replaces its old copy.
            Metadata meta = new Metadata.Builder().setClientRecordId("sthira-sleep-" + id)
                    .setClientRecordVersion(SleepStager.MODEL).build();
            SleepSessionRecord rec = new SleepSessionRecord.Builder(meta, Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs))
                    .setStartZoneOffset(off)
                    .setEndZoneOffset(off)
                    .setTitle("Sthira")
                    .setStages(list)
                    .build();
            hc.insertRecords(Collections.<Record>singletonList(rec), IO,
                    new OutcomeReceiver<InsertRecordsResponse, HealthConnectException>() {
                        @Override
                        public void onResult(InsertRecordsResponse r) {
                        }

                        @Override
                        public void onError(HealthConnectException e) {
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private static int type(char s, boolean withRem) {
        switch (s) {
            case 'D':
                return SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_DEEP;
            case 'R':
                return withRem ? SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_REM
                        : SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_LIGHT;
            case 'L':
                return SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_LIGHT;
            default:
                return SleepSessionRecord.StageType.STAGE_TYPE_AWAKE_IN_BED;
        }
    }
}
