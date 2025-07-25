/*Copyright (C) 2017 M. Steve Todd mstevetodd@gmail.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package jmri.enginedriver;

import static android.view.KeyEvent.KEYCODE_BACK;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.util.Log;
//import android.view.GestureDetector;
import android.view.GestureDetector.OnGestureListener;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Objects;

import jmri.enginedriver.type.Consist;
import jmri.enginedriver.type.Consist.ConLoco;
import jmri.enginedriver.type.activity_id_type;
import jmri.enginedriver.type.light_follow_type;
import jmri.enginedriver.type.message_type;
import jmri.enginedriver.import_export.ImportExportPreferences;
import jmri.enginedriver.util.LocaleHelper;

public class ConsistLightsEdit extends AppCompatActivity implements OnGestureListener {
    static final String activityName = "ConsistLightsEdit";

    public static String LIGHT_TEXT_OFF = "Off";
    public static String LIGHT_TEXT_ON = "On";
    public static String LIGHT_TEXT_FOLLOW = "Follow Fn Btn";
    public static String LIGHT_TEXT_UNKNOWN = "Unknown";

    static public final int RESULT_CON_LIGHTS_EDIT = RESULT_FIRST_USER;

    private threaded_application mainapp;  // hold pointer to mainapp
    private Menu CLEMenu;
    private ArrayList<HashMap<String, String>> consistList;
    private SimpleAdapter consistListAdapter;
//    private ArrayList<ConLoco> consistObjList;
    private Consist consist;
    private int result;                     // set to RESULT_FIRST_USER when something is edited

    private int whichThrottle;

    public ImportExportPreferences importExportPreferences = new ImportExportPreferences();

//    private GestureDetector myGesture;

    private SharedPreferences prefs;

    /** @noinspection FieldCanBeLocal*/
    private LinearLayout screenNameLine;
    /** @noinspection FieldCanBeLocal*/
    private Toolbar toolbar;
    /** @noinspection FieldCanBeLocal*/
    private LinearLayout statusLine;

    public void refreshConsistLists() {
        //clear and rebuild
//        consistObjList.clear();
//        int pos = 0;
        Collection<ConLoco> cgl = consist.getLocos(); //copy from synchronized map to avoid holding it while iterating
//        for (ConLoco l : cgl) {
//            if (l.isConfirmed()) {
//                consistObjList.add(l);
//                pos++;
//            }
//        }

        consistList.clear();
        for (ConLoco l : cgl) {
            if (l.isConfirmed()) {
                //put values into temp hashmap
                HashMap<String, String> hm = new HashMap<>();
                hm.put("lead_label", consist.getLeadAddr().equals(l.getAddress()) ? "LEAD" : "");
                hm.put("loco_addr", l.getAddress());
                hm.put("loco_name", l.toString());
                if (consist.getLeadAddr().equals(l.getAddress())) { // lead loco is always 'follow'
                    hm.put("loco_light", LIGHT_TEXT_FOLLOW);
                } else {
                    if (l.isLightOn() == light_follow_type.OFF) {
                        hm.put("loco_light", LIGHT_TEXT_OFF);
                        mainapp.forceFunction(mainapp.throttleIntToString(whichThrottle) + l.getAddress(), 0, false);
                    } else if (l.isLightOn() == light_follow_type.ON) {
                        hm.put("loco_light", LIGHT_TEXT_ON);
                        mainapp.forceFunction(mainapp.throttleIntToString(whichThrottle) + l.getAddress(), 0, true);
                    } else if (l.isLightOn() == light_follow_type.FOLLOW) {
                        hm.put("loco_light", LIGHT_TEXT_FOLLOW);
                    } else {
                        hm.put("loco_light", LIGHT_TEXT_UNKNOWN);
                    }
                }
                consistList.add(hm);
            }
        }

        consistListAdapter.notifyDataSetChanged();
        result = RESULT_CON_LIGHTS_EDIT;
    }


    //Handle messages from the communication thread back to this thread (responses from withrottle)
    @SuppressLint("HandlerLeak")
    class ConsistLightsEditHandler extends Handler {

        public ConsistLightsEditHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case message_type.RESPONSE:                       // see if loco added to or removed from any throttle
                    String response_str = msg.obj.toString();
                    if (response_str.length() >= 3) {
                        char com1 = response_str.charAt(0);
                        char com2 = response_str.charAt(2);
                        if (com1 == 'M' && (com2 == '+' || com2 == '-'))
                            refreshConsistLists();

                        String comA = response_str.substring(0, 3);
                        //update power icon
                        if ("PPA".equals(comA)) {
                            mainapp.setPowerStateActionViewButton(CLEMenu, findViewById(R.id.powerLayoutButton));
                        }
                    }
                    break;

                case message_type.REOPEN_THROTTLE:
                    if (threaded_application.currentActivity == activity_id_type.CONSIST_LIGHTS_EDIT)
                        endThisActivity();
                    break;

                case message_type.WIT_CON_RETRY:
                    witRetry(msg.obj.toString());
                    break;
                case message_type.WIT_CON_RECONNECT:
                    refreshConsistLists();
                    break;
                case message_type.RESTART_APP:
                case message_type.RELAUNCH_APP:
                case message_type.DISCONNECT:
                case message_type.SHUTDOWN:
                    shutdown();
                    break;
            }
        }
    }

    private void witRetry(String s) {
        Intent in = new Intent().setClass(this, reconnect_status.class);
        in.putExtra("status", s);
        startActivity(in);
        connection_activity.overridePendingTransition(this, R.anim.fade_in, R.anim.fade_out);
    }


//    @Override
//    public boolean onTouchEvent(MotionEvent event) {
//        return myGesture.onTouchEvent(event);
//    }

    /**
     * Called when the activity is first created.
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainapp = (threaded_application) getApplication();
        if (mainapp.isForcingFinish()) {     // expedite
            return;
        }
        prefs = getSharedPreferences("jmri.enginedriver_preferences", 0);

        mainapp.applyTheme(this);

        setContentView(R.layout.consist_lights);
        //put pointer to this activity's handler in main app's shared variable
        mainapp.consist_lights_edit_msg_handler = new ConsistLightsEditHandler(Looper.getMainLooper());
//        myGesture = new GestureDetector(this);

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            whichThrottle = mainapp.throttleCharToInt(extras.getChar("whichThrottle"));
        }

        LIGHT_TEXT_OFF = getApplicationContext().getResources().getString(R.string.lightsTextOff);
        LIGHT_TEXT_FOLLOW = getApplicationContext().getResources().getString(R.string.lightsTextFollow);
        LIGHT_TEXT_UNKNOWN = getApplicationContext().getResources().getString(R.string.lightsTextUnknown);

        if (mainapp.consists == null || mainapp.consists[whichThrottle] == null) {
            if (mainapp.consists == null)
                Log.d(threaded_application.applicationName, activityName + ": onCreate(): consists is null");
            else
                Log.d(threaded_application.applicationName, activityName + ": onCreate(): consists[" + whichThrottle + "] is null");
            threaded_application.activityInTransition(activityName);
            this.finish();
            return;
        }

        consist = mainapp.consists[whichThrottle];

        //Set up a list adapter to allow adding the list of recent connections to the UI.
        consistList = new ArrayList<>();
        consistListAdapter = new SimpleAdapter(this, consistList, R.layout.consist_lights_item,
                new String[]{"loco_name", "loco_addr", "loco_light"},
                new int[]{R.id.con_loco_name, R.id.con_loco_addr_hidden, R.id.con_loco_light});
        ListView consistLV = findViewById(R.id.consist_lights_list);
        consistLV.setAdapter(consistListAdapter);
        consistLV.setOnItemClickListener(new OnItemClickListener() {
            //When an entry is clicked, toggle the lights state for that loco
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                ViewGroup vg = (ViewGroup) v; //convert to ViewGroup for clicked row
                TextView addrv = (TextView) vg.getChildAt(1); // get address text from 2nd box
                String address = addrv.getText().toString();

                int light;
                if (consist.getLeadAddr().equals(address)) { // lead loco is always 'follow'
                    light = light_follow_type.FOLLOW;
                } else {
                    if ((consist.isLight(address) == light_follow_type.UNKNOWN) || (consist.isLight(address) == light_follow_type.FOLLOW)) {
                        light = light_follow_type.OFF;
                    } else if (consist.isLight(address) == light_follow_type.OFF) {
                        light = light_follow_type.ON;
                    } else {
                        light = light_follow_type.FOLLOW;
                    }
                }
                try {
                    consist.setLight(address, light);
                } catch (Exception e) {    // setLight returns null if address is not in consist - should not happen since address was selected from consist list
                    Log.d(threaded_application.applicationName, activityName + ": onCreat(): selected engine " + address + " that is not in consist");
                }
                mainapp.buttonVibration();
                refreshConsistLists();
            }
        });

        consistLV.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            //When an entry is long-clicked, toggle the lights state for that loco but don't send the command to the loco
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View v, int pos, long id) {
                ViewGroup vg = (ViewGroup) v;
                TextView addrv = (TextView) vg.getChildAt(1); // get address text from 2nd box
                String address = addrv.getText().toString();

                int light;
                if ((consist.isLight(address) == light_follow_type.UNKNOWN) | (consist.isLight(address) == light_follow_type.FOLLOW)) {
                    light = light_follow_type.OFF;
                } else if (consist.isLight(address) == light_follow_type.OFF) {
                    light = light_follow_type.ON;
                } else {
                    light = light_follow_type.FOLLOW;
                }
                try {
                    consist.setLight(address, light);
                } catch (Exception e) {    // setLight returns null if address is not in consist - should not happen since address was selected from consist list
                    Log.d(threaded_application.applicationName, activityName + ": onItemLongClick(): selected engine " + address + " that is not in consist");
                }
                refreshConsistLists();
                return true;
            }
        });

//        OnTouchListener gestureListener = new OnTouchListener() {
//            @SuppressLint("ClickableViewAccessibility")
//            public boolean onTouch(View v, MotionEvent event) {
//                mainapp.buttonVibration();
//                return myGesture.onTouchEvent(event);
//            }
//        };
//
//        consistLV.setOnTouchListener(gestureListener);

//        consistObjList = new ArrayList<>();

        //Set the buttons
        Button closeButton = findViewById(R.id.consist_lights_edit_button_close);
        closeButton.setOnClickListener(new close_button_listener(this));

        //update consist list
        refreshConsistLists();
        result = RESULT_OK;

        screenNameLine = findViewById(R.id.screen_name_line);
        toolbar = findViewById(R.id.toolbar);
        statusLine = findViewById(R.id.status_line);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            Objects.requireNonNull(getSupportActionBar()).setDisplayShowTitleEnabled(false);
            toolbar.showOverflowMenu();
            mainapp.setToolbarTitle(toolbar, statusLine, screenNameLine,
                    getApplicationContext().getResources().getString(R.string.app_name),
                    getApplicationContext().getResources().getString(R.string.app_name_ConsistLightsEdit),
                    "");
        }

    }  // end onCreate

    @Override
    public void onPause() {
        super.onPause();
        threaded_application.activityPaused(activityName);
    }

    @Override
    public void onResume() {
        super.onResume();
        threaded_application.activityResumed(activityName);

        threaded_application.currentActivity = activity_id_type.CONSIST_LIGHTS_EDIT;
        if (mainapp.isForcingFinish()) {     //expedite
            this.finish();
            return;
        }
        mainapp.setActivityOrientation(this);  //set screen orientation based on prefs
        if (CLEMenu != null) {
            mainapp.displayEStop(CLEMenu);
        }
        // suppress popup keyboard until EditText is touched
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        if (CLEMenu != null) {
            mainapp.displayFlashlightMenuButton(CLEMenu);
            mainapp.setFlashlightActionViewButton(CLEMenu, findViewById(R.id.flashlight_button));
            mainapp.displayPowerStateMenuButton(CLEMenu);
            mainapp.setPowerStateActionViewButton(CLEMenu, findViewById(R.id.powerLayoutButton));

        }
    }

    /**
     * Called when the activity is finished.
     */
    @Override
    public void onDestroy() {
        Log.d(threaded_application.applicationName, activityName + ": onDestroy()");
        super.onDestroy();

        importExportPreferences.loadRecentConsistsListFromFile();
        int whichEntryIsBeingUpdated = importExportPreferences.addCurrentConsistToBeginningOfList(consist);
        importExportPreferences.writeRecentConsistsListToFile(prefs, whichEntryIsBeingUpdated);

        if (mainapp.consist_lights_edit_msg_handler != null) {
            mainapp.consist_lights_edit_msg_handler.removeCallbacksAndMessages(null);
            mainapp.consist_lights_edit_msg_handler = null;
        } else {
            Log.d(threaded_application.applicationName, activityName + ": onDestroy(): mainapp.consist_lights_edit_msg_handler is null. Unable to removeCallbacksAndMessages");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.consist_lights_edit_menu, menu);
        CLEMenu = menu;
        mainapp.displayEStop(menu);
        mainapp.displayFlashlightMenuButton(menu);
        mainapp.setFlashlightActionViewButton(menu, findViewById(R.id.flashlight_button));
        mainapp.displayPowerStateMenuButton(menu);
        mainapp.setPowerStateActionViewButton(menu, findViewById(R.id.powerLayoutButton));

        adjustToolbarSize(menu);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle all of the possible menu actions.
        if (item.getItemId() == R.id.EmerStop) {
            mainapp.sendEStopMsg();
            mainapp.buttonVibration();
            return true;
        } else if (item.getItemId() == R.id.flashlight_button) {
            mainapp.toggleFlashlightActionView(this, CLEMenu, findViewById(R.id.flashlight_button));
            mainapp.buttonVibration();
            return true;
        } else if (item.getItemId() == R.id.powerLayoutButton) {
            if (!mainapp.isPowerControlAllowed()) {
                mainapp.powerControlNotAllowedDialog(CLEMenu);
            } else {
                mainapp.powerStateMenuButton();
            }
            mainapp.buttonVibration();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    //Always go to throttle if back button pressed
    @Override
    public boolean onKeyDown(int key, KeyEvent event) {
        mainapp.exitDoubleBackButtonInitiated = 0;
        if (key == KEYCODE_BACK) {
            endThisActivity();
            return true;
        }
        return (super.onKeyDown(key, event));
    }

    void endThisActivity() {
        threaded_application.activityInTransition(activityName);
        Intent resultIntent = new Intent();
        resultIntent.putExtra("whichThrottle", mainapp.throttleIntToChar(whichThrottle));  //pass whichThrottle as an extra
        setResult(result, resultIntent);
        this.finish();  //end this activity
        connection_activity.overridePendingTransition(this, R.anim.fade_in, R.anim.fade_out);
    }

    @Override
    public boolean onDown(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        return false;
    }

    @Override
    public void onShowPress(MotionEvent e) {
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }

    private void shutdown() {
        this.finish();
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.onAttach(base));
    }

    public class close_button_listener implements View.OnClickListener {
        Activity _consistEditLightsActivity;

        close_button_listener(Activity consistEditLightsActivity) {
            _consistEditLightsActivity = consistEditLightsActivity;
        }
        public void onClick(View v) {
            mainapp.buttonVibration();
            endThisActivity();
        }
    }

    void adjustToolbarSize(Menu menu) {
        ViewGroup.LayoutParams layoutParams = toolbar.getLayoutParams();
        int toolbarHeight = layoutParams.height;
        int newHeightAndWidth = toolbarHeight;

        if (!threaded_application.useSmallToolbarButtonSize) {
            newHeightAndWidth = toolbarHeight*2;
            layoutParams.height = newHeightAndWidth;
            toolbar.setLayoutParams(layoutParams);
        }
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            View itemChooser = item.getActionView();

            if (itemChooser != null) {
                itemChooser.getLayoutParams().height = newHeightAndWidth;
                itemChooser.getLayoutParams().width = (int) ( (float) newHeightAndWidth * 1.3 );

                itemChooser.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onOptionsItemSelected(item);
                    }
                });
            }
        }
    }
}