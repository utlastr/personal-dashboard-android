// MPL-2.0. Java port of parseForegroundSessions from aw-android SessionParser.kt.
// Changes: screen-off/shutdown boundaries and no open-session insertion until closed.
package local.personaldashboard.mobile;

import java.util.ArrayList;
import java.util.List;

public final class ForegroundParser {
    public static final class Event {
        public final int type;
        public final long time;
        public final String pkg, activity;
        public Event(int type, long time, String pkg, String activity) {
            this.type=type; this.time=time; this.pkg=pkg; this.activity=activity;
        }
    }
    public static final class Session {
        public final String pkg, activity;
        public final long start, end;
        public Session(String pkg, String activity, long start, long end) {
            this.pkg=pkg; this.activity=activity; this.start=start; this.end=end;
        }
    }
    public static final class Result {
        public final List<Session> sessions = new ArrayList<>();
        public long openStart = -1;
    }
    public static Result parse(List<Event> events) {
        Result result = new Result();
        String pkg=null, activity="";
        long start=0;
        for(Event event: events) {
            boolean resume=event.type==1;
            if(resume && event.pkg.equals(pkg)) continue;
            boolean end=resume || (event.type==2 && event.pkg.equals(pkg))
                || event.type==16 || event.type==26;
            if(end && pkg!=null) {
                long duration=event.time-start;
                if(duration>1000 && duration<86400000L)
                    result.sessions.add(new Session(pkg,activity,start,event.time));
                pkg=null;
            }
            if(resume) { pkg=event.pkg; activity=event.activity; start=event.time; }
        }
        if(pkg!=null) result.openStart=start;
        return result;
    }
}
