package it.niedermann.owncloud.notes.main;

import android.accounts.NetworkErrorException;
import android.app.Application;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;

import com.nextcloud.android.sso.AccountImporter;
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException;
import com.nextcloud.android.sso.exceptions.NextcloudHttpRequestFailedException;
import com.nextcloud.android.sso.helper.SingleAccountHelper;
import com.nextcloud.android.sso.model.SingleSignOnAccount;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import it.niedermann.owncloud.notes.BuildConfig;
import it.niedermann.owncloud.notes.R;
import it.niedermann.owncloud.notes.branding.BrandingUtil;
import it.niedermann.owncloud.notes.exception.IntendedOfflineException;
import it.niedermann.owncloud.notes.main.navigation.NavigationAdapter;
import it.niedermann.owncloud.notes.main.navigation.NavigationItem;
import it.niedermann.owncloud.notes.persistence.ApiProvider;
import it.niedermann.owncloud.notes.persistence.CapabilitiesClient;
import it.niedermann.owncloud.notes.persistence.NotesRepository;
import it.niedermann.owncloud.notes.persistence.entity.Account;
import it.niedermann.owncloud.notes.persistence.entity.CategoryWithNotesCount;
import it.niedermann.owncloud.notes.persistence.entity.Note;
import it.niedermann.owncloud.notes.persistence.entity.SingleNoteWidgetData;
import it.niedermann.owncloud.notes.shared.model.Capabilities;
import it.niedermann.owncloud.notes.shared.model.CategorySortingMethod;
import it.niedermann.owncloud.notes.shared.model.IResponseCallback;
import it.niedermann.owncloud.notes.shared.model.Item;
import it.niedermann.owncloud.notes.shared.model.NavigationCategory;

import static androidx.lifecycle.Transformations.distinctUntilChanged;
import static androidx.lifecycle.Transformations.map;
import static androidx.lifecycle.Transformations.switchMap;
import static it.niedermann.owncloud.notes.main.MainActivity.ADAPTER_KEY_RECENT;
import static it.niedermann.owncloud.notes.main.MainActivity.ADAPTER_KEY_STARRED;
import static it.niedermann.owncloud.notes.main.slots.SlotterUtil.fillListByCategory;
import static it.niedermann.owncloud.notes.main.slots.SlotterUtil.fillListByInitials;
import static it.niedermann.owncloud.notes.main.slots.SlotterUtil.fillListByTime;
import static it.niedermann.owncloud.notes.shared.model.CategorySortingMethod.SORT_MODIFIED_DESC;
import static it.niedermann.owncloud.notes.shared.model.ENavigationCategoryType.DEFAULT_CATEGORY;
import static it.niedermann.owncloud.notes.shared.model.ENavigationCategoryType.FAVORITES;
import static it.niedermann.owncloud.notes.shared.model.ENavigationCategoryType.RECENT;
import static it.niedermann.owncloud.notes.shared.model.ENavigationCategoryType.UNCATEGORIZED;
import static it.niedermann.owncloud.notes.shared.util.DisplayUtils.convertToCategoryNavigationItem;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;

public class MainViewModel extends AndroidViewModel {

    private static final String TAG = MainViewModel.class.getSimpleName();

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final SavedStateHandle state;

    private static final String KEY_CURRENT_ACCOUNT = "currentAccount";
    private static final String KEY_SEARCH_TERM = "searchTerm";
    private static final String KEY_SELECTED_CATEGORY = "selectedCategory";
    private static final String KEY_EXPANDED_CATEGORY = "expandedCategory";

    @NonNull
    private final NotesRepository repo;

    @NonNull
    private final MutableLiveData<Account> currentAccount = new MutableLiveData<>();
    @NonNull
    private final MutableLiveData<String> searchTerm = new MutableLiveData<>(null);
    @NonNull
    private final MutableLiveData<NavigationCategory> selectedCategory = new MutableLiveData<>(new NavigationCategory(RECENT));
    @NonNull
    private final MutableLiveData<String> expandedCategory = new MutableLiveData<>(null);

    public MainViewModel(@NonNull Application application, @NonNull SavedStateHandle savedStateHandle) {
        super(application);
        this.repo = NotesRepository.getInstance(application);
        this.state = savedStateHandle;
    }

    public void restoreInstanceState() {
        Log.v(TAG, "[restoreInstanceState]");
        final Account account = state.get(KEY_CURRENT_ACCOUNT);
        if (account != null) {
            postCurrentAccount(account);
        }
        postSearchTerm(state.get(KEY_SEARCH_TERM));
        final NavigationCategory selectedCategory = state.get(KEY_SELECTED_CATEGORY);
        if (selectedCategory != null) {
            postSelectedCategory(selectedCategory);
            Log.v(TAG, "[restoreInstanceState] - selectedCategory: " + selectedCategory);
        }
        postExpandedCategory(state.get(KEY_EXPANDED_CATEGORY));
    }

    @NonNull
    public LiveData<Account> getCurrentAccount() {
        return distinctUntilChanged(currentAccount);
    }

    public void postCurrentAccount(@NonNull Account account) {
        state.set(KEY_CURRENT_ACCOUNT, account);
        BrandingUtil.saveBrandColors(getApplication(), account.getColor(), account.getTextColor());
        SingleAccountHelper.setCurrentAccount(getApplication(), account.getAccountName());

        final Account currentAccount = this.currentAccount.getValue();
        // If only ETag or colors change, we must not reset the navigation
        // TODO in the long term we should store the last NavigationCategory for each Account
        if (currentAccount == null || currentAccount.getId() != account.getId()) {
            this.currentAccount.setValue(account);
            this.searchTerm.setValue("");
            this.selectedCategory.setValue(new NavigationCategory(RECENT));
        }
    }

    @NonNull
    public LiveData<String> getSearchTerm() {
        return distinctUntilChanged(searchTerm);
    }

    public void postSearchTerm(String searchTerm) {
        state.set(KEY_SEARCH_TERM, searchTerm);
        this.searchTerm.postValue(searchTerm);
    }

    @NonNull
    public LiveData<NavigationCategory> getSelectedCategory() {
        return distinctUntilChanged(selectedCategory);
    }

    public void postSelectedCategory(@NonNull NavigationCategory selectedCategory) {
        state.set(KEY_SELECTED_CATEGORY, selectedCategory);
        Log.v(TAG, "[postSelectedCategory] - selectedCategory: " + selectedCategory);
        this.selectedCategory.postValue(selectedCategory);

        // Close sub categories
        switch (selectedCategory.getType()) {
            case RECENT:
            case FAVORITES:
            case UNCATEGORIZED: {
                postExpandedCategory(null);
                break;
            }
            case DEFAULT_CATEGORY:
            default: {
                String category = selectedCategory.getCategory();
                if (category == null) {
                    postExpandedCategory(null);
                    Log.e(TAG, "navigation selection is a " + DEFAULT_CATEGORY + ", but the contained category is null.");
                } else {
                    int slashIndex = category.indexOf('/');
                    String rootCategory = slashIndex < 0 ? category : category.substring(0, slashIndex);
                    String expandedCategory = getExpandedCategory().getValue();
                    if (expandedCategory != null && !expandedCategory.equals(rootCategory)) {
                        postExpandedCategory(null);
                    }
                }
                break;
            }
        }
    }

    @NonNull
    @MainThread
    public LiveData<Pair<NavigationCategory, CategorySortingMethod>> getCategorySortingMethodOfSelectedCategory() {
        return switchMap(getSelectedCategory(), selectedCategory -> map(repo.getCategoryOrder(selectedCategory), sortingMethod -> new Pair<>(selectedCategory, sortingMethod)));
    }

    public LiveData<Void> modifyCategoryOrder(@NonNull NavigationCategory selectedCategory, @NonNull CategorySortingMethod sortingMethod) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return new MutableLiveData<>(null);
            } else {
                Log.v(TAG, "[modifyCategoryOrder] - currentAccount: " + currentAccount.getAccountName());
                repo.modifyCategoryOrder(currentAccount.getId(), selectedCategory, sortingMethod);
                return new MutableLiveData<>(null);
            }
        });
    }

    public void postExpandedCategory(@Nullable String expandedCategory) {
        state.set(KEY_EXPANDED_CATEGORY, expandedCategory);
        this.expandedCategory.postValue(expandedCategory);
    }

    @NonNull
    public LiveData<String> getExpandedCategory() {
        return distinctUntilChanged(expandedCategory);
    }

    @NonNull
    @MainThread
    public LiveData<List<Item>> getNotesListLiveData() {
        final MutableLiveData<List<Item>> insufficientInformation = new MutableLiveData<>();
        return distinctUntilChanged(switchMap(getCurrentAccount(), currentAccount -> {
            Log.v(TAG, "[getNotesListLiveData] - currentAccount: " + currentAccount);
            if (currentAccount == null) {
                return insufficientInformation;
            } else {
                return switchMap(getSelectedCategory(), selectedCategory -> {
                    if (selectedCategory == null) {
                        return insufficientInformation;
                    } else {
                        Log.v(TAG, "[getNotesListLiveData] - selectedCategory: " + selectedCategory);
                        return switchMap(getSearchTerm(), searchTerm -> {
                            Log.v(TAG, "[getNotesListLiveData] - searchTerm: " + (BuildConfig.DEBUG ? "******" : searchTerm));
                            return switchMap(getCategorySortingMethodOfSelectedCategory(), sortingMethod -> {
                                final long accountId = currentAccount.getId();
                                final String searchQueryOrWildcard = searchTerm == null ? "%" : "%" + searchTerm.trim() + "%";
                                Log.v(TAG, "[getNotesListLiveData] - sortMethod: " + sortingMethod.second);
                                final LiveData<List<Note>> fromDatabase;
                                switch (selectedCategory.getType()) {
                                    case RECENT: {
                                        Log.v(TAG, "[getNotesListLiveData] - category: " + RECENT);
                                        fromDatabase = sortingMethod.second == SORT_MODIFIED_DESC
                                                ? repo.searchRecentByModified$(accountId, searchQueryOrWildcard)
                                                : repo.searchRecentLexicographically$(accountId, searchQueryOrWildcard);
                                        break;
                                    }
                                    case FAVORITES: {
                                        Log.v(TAG, "[getNotesListLiveData] - category: " + FAVORITES);
                                        fromDatabase = sortingMethod.second == SORT_MODIFIED_DESC
                                                ? repo.searchFavoritesByModified$(accountId, searchQueryOrWildcard)
                                                : repo.searchFavoritesLexicographically$(accountId, searchQueryOrWildcard);
                                        break;
                                    }
                                    case UNCATEGORIZED: {
                                        Log.v(TAG, "[getNotesListLiveData] - category: " + UNCATEGORIZED);
                                        fromDatabase = sortingMethod.second == SORT_MODIFIED_DESC
                                                ? repo.searchUncategorizedByModified$(accountId, searchQueryOrWildcard)
                                                : repo.searchUncategorizedLexicographically$(accountId, searchQueryOrWildcard);
                                        break;
                                    }
                                    case DEFAULT_CATEGORY:
                                    default: {
                                        final String category = selectedCategory.getCategory();
                                        if (category == null) {
                                            throw new IllegalStateException(NavigationCategory.class.getSimpleName() + " type is " + DEFAULT_CATEGORY + ", but category is null.");
                                        }
                                        Log.v(TAG, "[getNotesListLiveData] - category: " + category);
                                        fromDatabase = sortingMethod.second == SORT_MODIFIED_DESC
                                                ? repo.searchCategoryByModified$(accountId, searchQueryOrWildcard, category)
                                                : repo.searchCategoryLexicographically$(accountId, searchQueryOrWildcard, category);
                                        break;
                                    }
                                }

                                Log.v(TAG, "[getNotesListLiveData] - -------------------------------------");
                                return distinctUntilChanged(map(fromDatabase, noteList -> fromNotes(noteList, selectedCategory, sortingMethod.second)));
                            });
                        });
                    }
                });
            }
        }));
    }

    private List<Item> fromNotes(List<Note> noteList, @NonNull NavigationCategory selectedCategory, @Nullable CategorySortingMethod sortingMethod) {
        if (selectedCategory.getType() == DEFAULT_CATEGORY) {
            final String category = selectedCategory.getCategory();
            if (category != null) {
                return fillListByCategory(noteList, category);
            } else {
                throw new IllegalStateException(NavigationCategory.class.getSimpleName() + " type is " + DEFAULT_CATEGORY + ", but category is null.");
            }
        }
        if (sortingMethod == SORT_MODIFIED_DESC) {
            return fillListByTime(getApplication(), noteList);
        } else {
            return fillListByInitials(getApplication(), noteList);
        }
    }

    @NonNull
    @MainThread
    public LiveData<List<NavigationItem>> getNavigationCategories() {
        final MutableLiveData<List<NavigationItem>> insufficientInformation = new MutableLiveData<>();
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return insufficientInformation;
            } else {
                Log.v(TAG, "[getNavigationCategories] - currentAccount: " + currentAccount.getAccountName());
                return switchMap(getExpandedCategory(), expandedCategory -> {
                    Log.v(TAG, "[getNavigationCategories] - expandedCategory: " + expandedCategory);
                    return switchMap(repo.count$(currentAccount.getId()), (count) -> {
                        Log.v(TAG, "[getNavigationCategories] - count: " + count);
                        return switchMap(repo.countFavorites$(currentAccount.getId()), (favoritesCount) -> {
                            Log.v(TAG, "[getNavigationCategories] - favoritesCount: " + favoritesCount);
                            return distinctUntilChanged(map(repo.getCategories$(currentAccount.getId()), fromDatabase ->
                                    fromCategoriesWithNotesCount(getApplication(), expandedCategory, fromDatabase, count, favoritesCount)
                            ));
                        });
                    });
                });
            }
        });
    }

    private static List<NavigationItem> fromCategoriesWithNotesCount(@NonNull Context context, @Nullable String expandedCategory, @NonNull List<CategoryWithNotesCount> fromDatabase, int count, int favoritesCount) {
        final List<NavigationItem.CategoryNavigationItem> categories = convertToCategoryNavigationItem(context, fromDatabase);
        final NavigationItem itemRecent = new NavigationItem(ADAPTER_KEY_RECENT, context.getString(R.string.label_all_notes), count, R.drawable.ic_access_time_grey600_24dp, RECENT);
        final NavigationItem itemFavorites = new NavigationItem(ADAPTER_KEY_STARRED, context.getString(R.string.label_favorites), favoritesCount, R.drawable.ic_star_yellow_24dp, FAVORITES);

        final ArrayList<NavigationItem> items = new ArrayList<>(fromDatabase.size() + 3);
        items.add(itemRecent);
        items.add(itemFavorites);
        NavigationItem lastPrimaryCategory = null;
        NavigationItem lastSecondaryCategory = null;
        for (NavigationItem item : categories) {
            int slashIndex = item.label.indexOf('/');
            String currentPrimaryCategory = slashIndex < 0 ? item.label : item.label.substring(0, slashIndex);
            String currentSecondaryCategory = null;
            boolean isCategoryOpen = currentPrimaryCategory.equals(expandedCategory);

            if (isCategoryOpen && !currentPrimaryCategory.equals(item.label)) {
                String currentCategorySuffix = item.label.substring(expandedCategory.length() + 1);
                int subSlashIndex = currentCategorySuffix.indexOf('/');
                currentSecondaryCategory = subSlashIndex < 0 ? currentCategorySuffix : currentCategorySuffix.substring(0, subSlashIndex);
            }

            boolean belongsToLastPrimaryCategory = lastPrimaryCategory != null && currentPrimaryCategory.equals(lastPrimaryCategory.label);
            boolean belongsToLastSecondaryCategory = belongsToLastPrimaryCategory && lastSecondaryCategory != null && lastSecondaryCategory.label.equals(currentPrimaryCategory + "/" + currentSecondaryCategory);

            if (isCategoryOpen && !belongsToLastPrimaryCategory && currentSecondaryCategory != null) {
                lastPrimaryCategory = new NavigationItem("category:" + currentPrimaryCategory, currentPrimaryCategory, 0, NavigationAdapter.ICON_MULTIPLE_OPEN);
                items.add(lastPrimaryCategory);
                belongsToLastPrimaryCategory = true;
            }

            if (belongsToLastPrimaryCategory && belongsToLastSecondaryCategory) {
                lastSecondaryCategory.count += item.count;
                lastSecondaryCategory.icon = NavigationAdapter.ICON_SUB_MULTIPLE;
            } else if (belongsToLastPrimaryCategory) {
                if (isCategoryOpen) {
                    item.label = currentSecondaryCategory;
                    item.id = "category:" + item.label;
                    item.icon = NavigationAdapter.ICON_SUB_FOLDER;
                    items.add(item);
                    lastSecondaryCategory = item;
                } else {
                    lastPrimaryCategory.count += item.count;
                    lastPrimaryCategory.icon = NavigationAdapter.ICON_MULTIPLE;
                    lastSecondaryCategory = null;
                }
            } else {
                if (isCategoryOpen) {
                    item.icon = NavigationAdapter.ICON_MULTIPLE_OPEN;
                } else {
                    item.label = currentPrimaryCategory;
                    item.id = "category:" + item.label;
                }
                items.add(item);
                lastPrimaryCategory = item;
                lastSecondaryCategory = null;
            }
        }
        return items;
    }

    public void synchronizeCapabilitiesAndNotes(@NonNull Account localAccount, @NonNull IResponseCallback<Void> callback) {
        Log.i(TAG, "[synchronizeCapabilitiesAndNotes] Synchronize capabilities for " + localAccount.getAccountName());
        synchronizeCapabilities(localAccount, new IResponseCallback<Void>() {
            @Override
            public void onSuccess(Void v) {
                Log.i(TAG, "[synchronizeCapabilitiesAndNotes] Synchronize notes for " + localAccount.getAccountName());
                synchronizeNotes(localAccount, callback);
            }

            @Override
            public void onError(@NonNull Throwable t) {
                callback.onError(t);
            }
        });
    }

    /**
     * Updates the network status if necessary and pulls the latest {@link Capabilities} of the given {@param localAccount}
     */
    public void synchronizeCapabilities(@NonNull Account localAccount, @NonNull IResponseCallback<Void> callback) {
        executor.submit(() -> {
            if (!repo.isSyncPossible()) {
                repo.updateNetworkStatus();
            }
            if (repo.isSyncPossible()) {
                try {
                    final SingleSignOnAccount ssoAccount = AccountImporter.getSingleSignOnAccount(getApplication(), localAccount.getAccountName());
                    try {
                        final Capabilities capabilities = CapabilitiesClient.getCapabilities(getApplication(), ssoAccount, localAccount.getCapabilitiesETag(), ApiProvider.getInstance());
                        repo.updateCapabilitiesETag(localAccount.getId(), capabilities.getETag());
                        repo.updateBrand(localAccount.getId(), capabilities.getColor(), capabilities.getTextColor());
                        localAccount.setColor(capabilities.getColor());
                        localAccount.setTextColor(capabilities.getTextColor());
                        BrandingUtil.saveBrandColors(getApplication(), localAccount.getColor(), localAccount.getTextColor());
                        repo.updateApiVersion(localAccount.getId(), capabilities.getApiVersion());
                        callback.onSuccess(null);
                    } catch (Throwable t) {
                        if (t.getClass() == NextcloudHttpRequestFailedException.class || t instanceof NextcloudHttpRequestFailedException) {
                            if (((NextcloudHttpRequestFailedException) t).getStatusCode() == HTTP_NOT_MODIFIED) {
                                Log.d(TAG, "Server returned HTTP Status Code " + ((NextcloudHttpRequestFailedException) t).getStatusCode() + " - Capabilities not modified.");
                                callback.onSuccess(null);
                                return;
                            }
                        }
                        callback.onError(t);
                    }
                } catch (NextcloudFilesAppAccountNotFoundException e) {
                    repo.deleteAccount(localAccount);
                    callback.onError(e);
                }
            } else {
                if (repo.isNetworkConnected() && repo.isSyncOnlyOnWifi()) {
                    callback.onError(new IntendedOfflineException("Network is connected, but sync is not possible."));
                } else {
                    callback.onError(new NetworkErrorException("Sync is not possible, because network is not connected."));
                }
            }
        }, "SYNC_CAPABILITIES");
    }

    /**
     * Updates the network status if necessary and pulls the latest notes of the given {@param localAccount}
     */
    public void synchronizeNotes(@NonNull Account currentAccount, @NonNull IResponseCallback<Void> callback) {
        executor.submit(() -> {
            Log.v(TAG, "[synchronize] - currentAccount: " + currentAccount.getAccountName());
            if (!repo.isSyncPossible()) {
                repo.updateNetworkStatus();
            }
            if (repo.isSyncPossible()) {
                repo.scheduleSync(currentAccount, false);
                callback.onSuccess(null);
            } else { // Sync is not possible
                if (repo.isNetworkConnected() && repo.isSyncOnlyOnWifi()) {
                    callback.onError(new IntendedOfflineException("Network is connected, but sync is not possible."));
                } else {
                    callback.onError(new NetworkErrorException("Sync is not possible, because network is not connected."));
                }
            }
        }, "SYNC_NOTES");
    }

    public LiveData<Boolean> getSyncStatus() {
        return repo.getSyncStatus();
    }

    public LiveData<ArrayList<Throwable>> getSyncErrors() {
        return repo.getSyncErrors();
    }

    public LiveData<Boolean> hasMultipleAccountsConfigured() {
        return map(repo.countAccounts$(), (counter) -> counter != null && counter > 1);
    }

    @WorkerThread
    public Account getLocalAccountByAccountName(String accountName) {
        return repo.getAccountByName(accountName);
    }

    @WorkerThread
    public List<Account> getAccounts() {
        return repo.getAccounts();
    }

    public LiveData<Void> setCategory(Iterable<Long> noteIds, @NonNull String category) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return new MutableLiveData<>(null);
            } else {
                Log.v(TAG, "[setCategory] - currentAccount: " + currentAccount.getAccountName());
                for (Long noteId : noteIds) {
                    repo.setCategory(currentAccount, noteId, category);
                }
                return new MutableLiveData<>(null);
            }
        });
    }

    public LiveData<Note> moveNoteToAnotherAccount(Account account, long noteId) {
        return switchMap(repo.getNoteById$(noteId), (note) -> {
            Log.v(TAG, "[moveNoteToAnotherAccount] - note: " + (BuildConfig.DEBUG ? note : note.getTitle()));
            return repo.moveNoteToAnotherAccount(account, note);
        });
    }

    public LiveData<Void> toggleFavoriteAndSync(long noteId) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return new MutableLiveData<>(null);
            } else {
                Log.v(TAG, "[toggleFavoriteAndSync] - currentAccount: " + currentAccount.getAccountName());
                repo.toggleFavoriteAndSync(currentAccount, noteId);
                return new MutableLiveData<>(null);
            }
        });
    }

    public LiveData<Void> deleteNoteAndSync(long id) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return new MutableLiveData<>(null);
            } else {
                Log.v(TAG, "[deleteNoteAndSync] - currentAccount: " + currentAccount.getAccountName());
                repo.deleteNoteAndSync(currentAccount, id);
                return new MutableLiveData<>(null);
            }
        });
    }

    public LiveData<Void> deleteNotesAndSync(@NonNull Collection<Long> ids) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return new MutableLiveData<>(null);
            } else {
                Log.v(TAG, "[deleteNotesAndSync] - currentAccount: " + currentAccount.getAccountName());
                for (Long id : ids) {
                    repo.deleteNoteAndSync(currentAccount, id);
                }
                return new MutableLiveData<>(null);
            }
        });
    }

    public void addAccount(@NonNull String url, @NonNull String username, @NonNull String accountName, @NonNull Capabilities capabilities, @Nullable String displayName, @NonNull IResponseCallback<Account> callback) {
        repo.addAccount(url, username, accountName, capabilities, displayName, callback);
    }

    public LiveData<Note> getFullNote$(long id) {
        return map(getFullNotesWithCategory(Collections.singleton(id)), input -> input.get(0));
    }

    @WorkerThread
    public Note getFullNote(long id) {
        return repo.getNoteById(id);
    }

    public LiveData<List<Note>> getFullNotesWithCategory(@NonNull Collection<Long> ids) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return new MutableLiveData<>();
            } else {
                Log.v(TAG, "[getNote] - currentAccount: " + currentAccount.getAccountName());
                final MutableLiveData<List<Note>> notes = new MutableLiveData<>();
                executor.submit(() -> notes.postValue(
                        ids
                                .stream()
                                .map(repo::getNoteById)
                                .collect(Collectors.toList())
                ));
                return notes;
            }
        });
    }

    public LiveData<Note> addNoteAndSync(Note note) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return new MutableLiveData<>();
            } else {
                Log.v(TAG, "[addNoteAndSync] - currentAccount: " + currentAccount.getAccountName());
                return repo.addNoteAndSync(currentAccount, note);
            }
        });
    }

    public LiveData<Void> updateNoteAndSync(@NonNull Note oldNote, @Nullable String newContent, @Nullable String newTitle) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount != null) {
                Log.v(TAG, "[updateNoteAndSync] - currentAccount: " + currentAccount.getAccountName());
                repo.updateNoteAndSync(currentAccount, oldNote, newContent, newTitle, null);
            }
            return new MutableLiveData<>(null);
        });
    }

    public void createOrUpdateSingleNoteWidgetData(SingleNoteWidgetData data) {
        repo.createOrUpdateSingleNoteWidgetData(data);
    }

    public List<Note> getLocalModifiedNotes(long accountId) {
        return repo.getLocalModifiedNotes(accountId);
    }

    public LiveData<Integer> getAccountsCount() {
        return repo.countAccounts$();
    }

    @WorkerThread
    public String collectNoteContents(@NonNull List<Long> noteIds) {
        final StringBuilder noteContents = new StringBuilder();
        for (Long noteId : noteIds) {
            final Note fullNote = repo.getNoteById(noteId);
            final String tempFullNote = fullNote.getContent();
            if (!TextUtils.isEmpty(tempFullNote)) {
                if (noteContents.length() > 0) {
                    noteContents.append("\n\n");
                }
                noteContents.append(tempFullNote);
            }
        }
        return noteContents.toString();
    }
}