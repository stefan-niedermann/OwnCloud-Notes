package it.niedermann.owncloud.notes.main.navigation;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.RecyclerView;

import it.niedermann.owncloud.notes.R;
import it.niedermann.owncloud.notes.databinding.ItemNavigationBinding;
import it.niedermann.owncloud.notes.shared.util.NoteUtil;

import static java.util.Objects.requireNonNull;

class NavigationViewHolder extends RecyclerView.ViewHolder {
    @NonNull
    private final View view;

    @NonNull
    private final TextView name;
    @NonNull
    private final TextView count;
    @NonNull
    private final ImageView icon;

    private NavigationItem currentItem;

    NavigationViewHolder(@NonNull View itemView, @NonNull final NavigationClickListener navigationClickListener) {
        super(itemView);
        view = itemView;
        ItemNavigationBinding binding = ItemNavigationBinding.bind(view);
        this.name = binding.navigationItemLabel;
        this.count = binding.navigationItemCount;
        this.icon = binding.navigationItemIcon;
        icon.setOnClickListener(view -> navigationClickListener.onIconClick(currentItem));
        itemView.setOnClickListener(view -> navigationClickListener.onItemClick(currentItem));
    }

    public void bind(@NonNull NavigationItem item, @ColorInt int mainColor, String selectedItem) {
        currentItem = item;
        boolean isSelected = item.id.equals(selectedItem);
        name.setText(NoteUtil.extendCategory(item.label));
        count.setVisibility(item.count == null ? View.GONE : View.VISIBLE);
        count.setText(String.valueOf(item.count));
        if (item.icon > 0) {
            icon.setImageDrawable(DrawableCompat.wrap(requireNonNull(ContextCompat.getDrawable(icon.getContext(), item.icon))));
            icon.setVisibility(View.VISIBLE);
        } else {
            icon.setVisibility(View.GONE);
        }
        int textColor = isSelected ? mainColor : view.getResources().getColor(R.color.fg_default);

        name.setTextColor(textColor);
        count.setTextColor(textColor);
        icon.setColorFilter(isSelected ? textColor : 0);

        view.setSelected(isSelected);

        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        params.leftMargin = item.icon == NavigationAdapter.ICON_SUB_FOLDER ? view.getResources().getDimensionPixelSize(R.dimen.margin_25) : 0;
        view.requestLayout();
    }
}