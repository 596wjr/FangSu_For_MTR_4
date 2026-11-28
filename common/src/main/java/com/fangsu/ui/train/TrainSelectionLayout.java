package com.fangsu.ui.train;

import com.fangsu.train.selection.TrainCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

final class TrainSelectionLayout {
    static final int MARGIN = 8;
    static final int PANE_TOP = 61;
    static final int FOOTER_HEIGHT = 52;
    static final int FORMATION_ROW = 30;
    static final int TAG_ROW = 17;
    private static final int GAP = 5;
    private static final int CARD_HEADER = 24;
    private static final int CAR_BUTTON_HEIGHT = 17;

    final Rect library;
    final Rect formation;
    final Rect details;

    TrainSelectionLayout(int width, int height) {
        int room = width - 2 * MARGIN - 2 * GAP;
        int libraryWidth = room * 42 / 100;
        int formationWidth = room * 33 / 100;
        int bottom = Math.max(PANE_TOP + 1, height - FOOTER_HEIGHT);
        library = new Rect(MARGIN, PANE_TOP, MARGIN + libraryWidth, bottom);
        formation = new Rect(library.right + GAP, PANE_TOP, library.right + GAP + formationWidth, bottom);
        details = new Rect(formation.right + GAP, PANE_TOP, width - MARGIN, bottom);
    }

    LibraryRows layoutLibrary(List<TrainCatalog.Variant> variants, ToIntFunction<String> textWidth) {
        List<Card> cards = new ArrayList<>();
        int top = library.top + 3;
        int left = library.left + 3;
        int right = library.right - 6;
        for (TrainCatalog.Variant variant : variants) {
            int buttonX = left + 9;
            int availableWidth = Math.max(1, right - buttonX - 3);
            int desired = variant.visibleCars().stream().mapToInt(car -> textWidth.applyAsInt(car.role().label()) + 10)
                    .max().orElse(23);
            int cellWidth = Math.min(availableWidth, Math.max(desired, 23));
            int columns = Math.max(1, (availableWidth + 3) / (cellWidth + 3));
            List<CarButton> buttons = new ArrayList<>();
            for (int index = 0; index < variant.visibleCars().size(); index++) {
                int x = buttonX + index % columns * (cellWidth + 3);
                int y = top + CARD_HEADER + index / columns * (CAR_BUTTON_HEIGHT + 2);
                buttons.add(new CarButton(new Rect(x, y, x + cellWidth, y + CAR_BUTTON_HEIGHT), variant.visibleCars().get(index)));
            }
            int bottom = buttons.get(buttons.size() - 1).bounds.bottom + 3;
            cards.add(new Card(new Rect(left, top, right, bottom), new Rect(left + 7, top + 2, right - 2, top + CARD_HEADER), variant, buttons));
            top = bottom + 3;
        }
        return new LibraryRows(cards, top - library.top);
    }

    Rect formationRow(int index, int scroll) {
        int top = formation.top + 4 + FORMATION_ROW * index - scroll;
        return new Rect(formation.left + 3, top, formation.right - 6, top + FORMATION_ROW - 3);
    }

    Rect tagRow(int index, int scroll) {
        int top = library.top + 4 + TAG_ROW * index - scroll;
        return new Rect(library.left + 3, top, library.right - 6, top + TAG_ROW - 2);
    }

    int insertion(double mouseY, int count, int scroll) {
        int relative = (int) Math.floor(mouseY - formation.top - 4 + scroll);
        return Math.max(0, Math.min(count, (relative + FORMATION_ROW / 2) / FORMATION_ROW));
    }

    static int clampScroll(int scroll, Rect viewport, int contentHeight) {
        return Math.max(0, Math.min(scroll, contentHeight - viewport.height()));
    }

    static Scrollbar scrollbar(Rect viewport, int contentHeight) {
        int range = contentHeight - viewport.height();
        if (range <= 0 || viewport.height() < 2) return null;
        int height = viewport.height();
        int thumb = Math.min(height - 1, Math.max(8, (int) ((long) height * height / contentHeight)));
        Rect track = new Rect(viewport.right - 5, viewport.top, viewport.right, viewport.bottom);
        return new Scrollbar(track, thumb, range);
    }

    record Scrollbar(Rect track, int thumbHeight, int range) {
        Rect thumb(int scroll) {
            int top = (int) Math.round(thumbTop(scroll));
            return new Rect(track.left, top, track.right, top + thumbHeight);
        }

        double grabOffset(double y, int scroll) {
            return y - thumbTop(scroll);
        }

        int scrollAt(double y, double grabOffset) {
            double ratio = (y - track.top - grabOffset) / (track.height() - thumbHeight);
            return (int) Math.round(Math.max(0, Math.min(1, ratio)) * range);
        }

        private double thumbTop(int scroll) {
            return track.top + (double) scroll / range * (track.height() - thumbHeight);
        }
    }

    record Rect(int left, int top, int right, int bottom) {
        int width() { return right - left; }
        int height() { return bottom - top; }
        int centerX() { return (left + right) / 2; }
        boolean contains(double x, double y) { return x >= left && x < right && y >= top && y < bottom; }
        boolean intersectsVertically(Rect viewport) { return top < viewport.bottom && bottom > viewport.top; }
        Rect shiftY(int dy) { return new Rect(left, top + dy, right, bottom + dy); }
        Rect removeButton() { return new Rect(right - 15, top + 5, right - 2, bottom - 5); }
    }

    record CarButton(Rect bounds, TrainCatalog.Car car) {}
    record Card(Rect bounds, Rect title, TrainCatalog.Variant variant, List<CarButton> buttons) {}
    record LibraryRows(List<Card> cards, int height) {}
}
