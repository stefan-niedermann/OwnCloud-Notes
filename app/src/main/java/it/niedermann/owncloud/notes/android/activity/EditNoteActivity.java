package it.niedermann.owncloud.notes.android.activity;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import it.niedermann.owncloud.notes.R;
import it.niedermann.owncloud.notes.model.DBNote;
import it.niedermann.owncloud.notes.persistence.NoteSQLiteOpenHelper;
import it.niedermann.owncloud.notes.util.ICallback;

public class EditNoteActivity extends AppCompatActivity {

    public static final String PARAM_NOTE = "note";
    public static final String PARAM_NOTE_POSITION = "note_position";

    private static final String LOG_TAG = "EditNote/SAVE";
    private static final long DELAY = 2000; // in ms
    private static final long DELAY_AFTER_SYNC = 5000; // in ms

    private EditText content = null;
    private DBNote note = null;
    private int notePosition = 0;
    private Timer timer, timerNextSync;
    private boolean saveActive = false;
    private ActionBar actionBar;
    private NoteSQLiteOpenHelper db;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);
        if (savedInstanceState == null) {
            Log.d(getClass().getSimpleName(), "Starting from Intent");
            note = (DBNote) getIntent().getSerializableExtra(PARAM_NOTE);
            notePosition = getIntent().getIntExtra(PARAM_NOTE_POSITION, 0);
        } else {
            Log.d(getClass().getSimpleName(), "Starting from SavedState");
            note = (DBNote) savedInstanceState.getSerializable(PARAM_NOTE);
            notePosition = savedInstanceState.getInt(PARAM_NOTE_POSITION);
        }
        content = (EditText) findViewById(R.id.editContent);
        content.setText(note.getContent());
        content.setEnabled(true);
        db = new NoteSQLiteOpenHelper(this);
        actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(note.getTitle());
            actionBar.setSubtitle(getString(R.string.action_edit_editing));
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        content.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(final CharSequence s, int start, int before, int count) {
                if (timer != null) {
                    timer.cancel();
                    timer = null;
                }
            }

            @Override
            public void afterTextChanged(final Editable s) {
                if(db.getNoteServerSyncHelper().isSyncPossible()) {
                    if(timer != null) {
                        timer.cancel();
                    }
                    if(!saveActive) {
                        timer = new Timer();
                        timer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        autoSave();
                                    }
                                });
                            }
                        }, DELAY);
                    }
                }
            }
        });
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putSerializable(PARAM_NOTE, note);
        outState.putInt(PARAM_NOTE_POSITION, notePosition);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        saveAndClose();
    }

    /**
     * Main-Menu
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_note_list_view, menu);
        return true;
    }

    /**
     * Main-Menu-Handler
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        NoteSQLiteOpenHelper db;
        switch (item.getItemId()) {
            case android.R.id.home:
                saveAndClose();
                return true;
            case R.id.menu_delete:
                db = new NoteSQLiteOpenHelper(this);
                db.deleteNoteAndSync(note.getId());
                Intent data = new Intent();
                data.putExtra(PARAM_NOTE_POSITION, notePosition);
                setResult(RESULT_FIRST_USER, data);
                finish();
                return true;
            case R.id.menu_preview:
                saveData(null);
                Intent previewIntent = new Intent(getApplicationContext(), NoteActivity.class);
                previewIntent.putExtra(NoteActivity.PARAM_NOTE, note);
                startActivity(previewIntent);
                return true;
            case R.id.menu_share:
                Intent shareIntent = new Intent();
                shareIntent.setAction(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, note.getTitle());
                shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, note.getContent());
                startActivity(shareIntent);
                return true;
            /*case R.id.menu_copy:
                db = new NoteSQLiteOpenHelper(this);
                Note newNote = db.getNote(db.addNoteAndSync(note.getContent()));
                newNote.setTitle(note.getTitle() + " (" + getResources().getString(R.string.copy) + ")");
                db.updateNote(newNote);
                finish();
                return true;*/
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Saves all changes and closes the Activity
     */
    private void saveAndClose() {
        content.setEnabled(false);
        if(timer!=null) {
            timer.cancel();
            timer = null;
        }
        if(timerNextSync!=null) {
            timerNextSync.cancel();
            timerNextSync = null;
        }
        saveData(null);
        Intent data = new Intent();
        data.setAction(Intent.ACTION_VIEW);
        data.putExtra(PARAM_NOTE, note);
        data.putExtra(PARAM_NOTE_POSITION, notePosition);
        setResult(RESULT_OK, data);
        finish();
    }

    /**
     * Gets the current content of the EditText field in the UI.
     * @return String of the current content.
     */
    private String getContent() {
        return ((EditText) findViewById(R.id.editContent)).getText().toString();
    }

    /**
     * Saves the current changes and show the status in the ActionBar
     */
    private void autoSave() {
        Log.d(LOG_TAG, "START save+sync");
        saveActive = true;
        if (actionBar != null) {
            actionBar.setSubtitle(getString(R.string.action_edit_saving));
        }
        final String content = getContent();
        saveData(new ICallback() {
            @Override
            public void onFinish() {
                // AFTER SYNCHRONIZATION
                Log.d(LOG_TAG, "...sync finished");
                actionBar.setTitle(note.getTitle());
                actionBar.setSubtitle(getResources().getString(R.string.action_edit_saved));
                Executors.newSingleThreadScheduledExecutor().schedule(new Runnable() {
                    @Override
                    public void run() {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // AFTER 1 SECOND: set ActionBar to default title
                                actionBar.setSubtitle(getString(R.string.action_edit_editing));
                            }
                        });
                    }
                }, 1, TimeUnit.SECONDS);

                timerNextSync = new Timer();
                timerNextSync.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // AFTER "DELAY_AFTER_SYNC" SECONDS: allow next auto-save or start it directly
                                if(getContent().equals(content)) {
                                    saveActive = false;
                                    Log.d(LOG_TAG, "FINISH, no new changes");
                                } else {
                                    Log.d(LOG_TAG, "content has changed meanwhile -> restart save");
                                    autoSave();
                                }
                            }
                        });
                    }
                }, DELAY_AFTER_SYNC);

                /* TODO Notify widgets

                int widgetIDs[] = AppWidgetManager.getInstance(getApplication()).getAppWidgetIds(new ComponentName(getApplication(), SingleNoteWidget.class));

                for (int id : widgetIDs) {
                    AppWidgetManager.getInstance(getApplication()).notifyAppWidgetViewDataChanged(id, R.layout.widget_single_note);
                }*/
            }
        });
    }

    /**
     * Save the current state in the database and schedule synchronization if needed.
     * @param callback
     */
    private void saveData(ICallback callback) {
        note = db.updateNoteAndSync(note, getContent(), callback);
    }
}