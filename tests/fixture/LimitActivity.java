package fixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class LimitActivity extends Activity {
    public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text=new TextView(this);
        text.setText("Isolated app-limit test fixture");
        text.setTextSize(24);
        setContentView(text);
    }
}
