package it.niedermann.owncloud.notes.edit.title;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import it.niedermann.owncloud.notes.R;
import it.niedermann.owncloud.notes.databinding.DialogEditTitleBinding;

public class EditTitleDialogFragment extends DialogFragment {

    private static final String TAG = EditTitleDialogFragment.class.getSimpleName();
    static final String PARAM_OLD_TITLE = "old_title";
    private DialogEditTitleBinding binding;

    private String oldTitle;
    private EditTitleListener listener;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        final Bundle args = getArguments();
        if (args == null) {
            throw new IllegalArgumentException("Provide at least " + PARAM_OLD_TITLE);
        }
        oldTitle = args.getString(PARAM_OLD_TITLE);

        if (getTargetFragment() instanceof EditTitleListener) {
            listener = (EditTitleListener) getTargetFragment();
        } else if (getActivity() instanceof EditTitleListener) {
            listener = (EditTitleListener) getActivity();
        } else {
            throw new IllegalArgumentException("Calling activity or target fragment must implement " + EditTitleListener.class.getSimpleName());
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View dialogView = View.inflate(getContext(), R.layout.dialog_edit_title, null);
        binding = DialogEditTitleBinding.bind(dialogView);

        if (savedInstanceState == null) {
            binding.title.setText(oldTitle);
        }

        return new AlertDialog.Builder(getActivity())
                .setTitle(R.string.change_note_title)
                .setView(dialogView)
                .setCancelable(false)
                .setPositiveButton(R.string.action_edit_save, (dialog, which) -> {
                    hideKeyboard(dialogView.findViewById(R.id.title).getWindowToken());
                    listener.onTitleEdited(binding.title.getText().toString());
                })
                .setNegativeButton(R.string.simple_cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        hideKeyboard(dialogView.findViewById(R.id.title).getWindowToken());
                    }
                })
                .create();
    }

    private void hideKeyboard(IBinder windowToken) {
        InputMethodManager inputManager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        inputManager.hideSoftInputFromWindow(windowToken, InputMethodManager.HIDE_NOT_ALWAYS);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        binding.title.requestFocus();
        Window window = requireDialog().getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        } else {
            Log.w(TAG, "can not enable soft keyboard because " + Window.class.getSimpleName() + " is null.");
        }
    }

    public static DialogFragment newInstance(String title) {
        final DialogFragment fragment = new EditTitleDialogFragment();
        final Bundle args = new Bundle();
        args.putString(PARAM_OLD_TITLE, title);
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * Interface that must be implemented by the calling Activity.
     */
    public interface EditTitleListener {
        /**
         * This method is called after the user has changed the title of a note manually.
         *
         * @param newTitle the new title that a user submitted
         */
        void onTitleEdited(String newTitle);
    }
}
