package com.fangsu.ui.train;

import com.fangsu.mappings.ComponentHelper;
import com.fangsu.train.selection.TrainCatalog;
import com.fangsu.train.selection.TrainFormation;
import com.fangsu.utils.GraphicContext;
//#if MC_VERSION >= 12000
import net.minecraft.client.gui.GuiGraphics;
//#endif
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.mtr.core.data.Siding;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.packet.PacketUpdateData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

import static com.fangsu.ui.train.TrainSelectionLayout.MARGIN;
import static com.fangsu.ui.train.TrainSelectionLayout.Rect;

public final class TrainSelectionScreen extends Screen {
    private static final String LANG = "ui.fangsu.train_selection.";
    private static final int TEXT = 0xFFF1F3F5;
    private static final int MUTED = 0xFFB5BDC7;
    private static final int BACKGROUND = 0xFF202020;
    private static final int PANE = 0xFF111418;
    private static final int ROW = 0xFF242A31;
    private static final int HOVER = 0xFF39454F;
    private static final int ACCENT = 0xFF93C7FA;
    private static final int ERROR = 0xFFFF9B96;
    private static final long TEXT_SCROLL_MILLIS_PER_PIXEL = 40;
    private static final long TEXT_SCROLL_PAUSE_MILLIS = 500;

    private final Screen parent;
    private final Siding siding;
    private final TrainCatalog catalog;
    private final TrainFormation formation;
    private final List<VehicleCar> original;
    private final List<VehicleCar> draft;
    private final Map<String, Set<String>> filters = new TreeMap<>();
    private final Set<String> expandedTags = new HashSet<>();

    private TrainSelectionLayout layout;
    private TrainSelectionLayout.LibraryRows libraryRows;
    private List<TagRow> tagRows = List.of();
    private EditBox search;
    private Button filterButton;
    private Button clearFilters;
    private Button duplicateButton;
    private List<Button> buttons = List.of();
    private boolean showingFilters;
    private String query = "";
    private Component notice;
    private int libraryScroll;
    private int tagScroll;
    private int formationScroll;
    private int detailsScroll;
    private List<FormattedCharSequence> detailsLines = List.of();
    private String detailId;
    private double detailLength;
    private String wikiText;
    private CompletableFuture<String> wikiRequest;
    private Drag drag;
    private ScrollDrag scrollDrag;
    private boolean draggingCar;
    private long textScrollStart = System.nanoTime();
    private long textScrollMillis;
    private double pointerX;
    private double pointerY;

    public TrainSelectionScreen(Siding siding, Screen parent) {
        super(text("title"));
        this.siding = siding;
        this.parent = parent;
        catalog = new TrainCatalog(siding.getTransportMode());
        formation = new TrainFormation(siding);
        original = List.copyOf(siding.getVehicleCars());
        draft = new ArrayList<>(original);
    }

    @Override
    protected void init() {
        layout = new TrainSelectionLayout(width, height);
        drag = null;
        scrollDrag = null;
        draggingCar = false;
        int right = width - MARGIN;
        int actionWidth = Math.max(36, Math.min(95, (width - 28) / 4));
        int filterX = right - actionWidth * 2 - 4;
        Component placeholder = ComponentHelper.translatable("ui.fangsu.common.search");
        search = addRenderableWidget(new EditBox(font, MARGIN, 24, Math.max(1, filterX - MARGIN - 4), 20, placeholder));
        search.setMaxLength(256);
        search.setValue(query);
        search.setSuggestion(query.isEmpty() ? placeholder.getString() : null);
        search.setResponder(value -> {
            query = value;
            search.setSuggestion(value.isEmpty() ? placeholder.getString() : null);
            rebuildLibrary(true);
        });
        filterButton = addRenderableWidget(button(text("filters"), filterX, 24, actionWidth, this::toggleFilters));
        clearFilters = addRenderableWidget(button(text("clear_filters"), right - actionWidth, 24, actionWidth, () -> {
            filters.clear();
            rebuildLibrary(true);
            refreshButtons();
        }));

        int footerY = height - 26;
        int footerWidth = (width - MARGIN * 2 - 8) / 3;
        duplicateButton = addRenderableWidget(button(text("duplicate"), MARGIN, footerY, footerWidth, this::duplicate));
        Button cancelButton = addRenderableWidget(button(ComponentHelper.translatable("gui.cancel"), MARGIN + footerWidth + 4,
                footerY, footerWidth, this::onClose));
        Button saveButton = addRenderableWidget(button(ComponentHelper.translatable("ui.fangsu.block.close_and_save"), MARGIN + (footerWidth + 4) * 2,
                footerY, footerWidth, this::save));
        buttons = List.of(filterButton, clearFilters, duplicateButton, cancelButton, saveButton);
        rebuildLibrary(false);
        rebuildTags();
        refreshButtons();
        rebuildDetails();
        clampFormationScroll();
    }

    private static Button button(Component label, int x, int y, int width, Runnable action) {
        return ComponentHelper.button(x, y, width, 20, label, button -> action.run());
    }

    private void refreshButtons() {
        int count = filters.values().stream().mapToInt(Set::size).sum();
        filterButton.setMessage(showingFilters ? text("back_to_trains") : count == 0 ? text("filters") : text("filters_count", count));
        clearFilters.active = count != 0;
        duplicateButton.active = !draft.isEmpty();
    }

    private void toggleFilters() {
        showingFilters = !showingFilters;
        textScrollStart = System.nanoTime();
        refreshButtons();
    }

    private void rebuildLibrary(boolean resetScroll) {
        libraryRows = layout.layoutLibrary(catalog.filter(query, filters), font::width);
        libraryScroll = TrainSelectionLayout.clampScroll(resetScroll ? 0 : libraryScroll, layout.library, libraryRows.height());
    }

    private void rebuildTags() {
        List<TagRow> rows = new ArrayList<>();
        catalog.tags().forEach((category, values) -> {
            rows.add(new TagRow(category, null));
            if (expandedTags.contains(category)) values.forEach(value -> rows.add(new TagRow(category, value)));
        });
        tagRows = rows;
        tagScroll = TrainSelectionLayout.clampScroll(tagScroll, layout.library, tagRowsHeight());
    }

    @Override
    public void tick() {
        super.tick();
        //#if MC_VERSION < 12003
        search.tick();
        //#endif
        if (draggingCar && layout.formation.contains(pointerX, pointerY)) {
            if (pointerY < layout.formation.top() + 12) formationScroll -= 8;
            if (pointerY >= layout.formation.bottom() - 12) formationScroll += 8;
            clampFormationScroll();
        }
    }

    //#if MC_VERSION >= 12000
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        GraphicContext g = GraphicContext.of(guiGraphics);
    //#else
    //$$ @Override
    //$$ public void render(com.mojang.blaze3d.vertex.PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
    //$$     GraphicContext g = GraphicContext.of(poseStack);
    //#endif
        pointerX = mouseX;
        pointerY = mouseY;
        textScrollMillis = (System.nanoTime() - textScrollStart) / 1_000_000L;
        if (scrollDrag == null) updateHover(mouseX, mouseY);
        g.fill(0, 0, width, height, BACKGROUND);
        g.drawString(font, title, MARGIN, 8, TEXT, false);
        pane(g, layout.library, showingFilters ? text("filters") : text("available"));
        pane(g, layout.formation, text("selected"));
        pane(g, layout.details, text("details"));
        if (showingFilters) drawTags(g, mouseX, mouseY);
        else drawLibrary(g, mouseX, mouseY);
        drawFormation(g, mouseX, mouseY);
        drawDetails(g);
        g.drawString(font, text("capacity", draft.size(), meters(formation.usedLength(draft)), meters(siding.getRailLength())),
                MARGIN, height - 47, MUTED, false);
        if (notice != null) drawScrollingText(g, null, notice.getString(), MARGIN, height - 36, width - MARGIN * 2, ERROR);
        // 1.20.4 的 Screen.render 会重画背景，直接绘制控件以保留三栏内容。
        search.render(g.asMinecraft(), mouseX, mouseY, partialTick);
        for (Button button : buttons) button.render(g.asMinecraft(), mouseX, mouseY, partialTick);
        if (draggingCar) drawDrag(g, mouseX, mouseY);
    }

    private void pane(GraphicContext g, Rect pane, Component label) {
        g.drawCenteredString(font, label, pane.centerX(), pane.top() - 12, TEXT);
        fill(g, pane, PANE);
    }

    private void drawLibrary(GraphicContext g, int mouseX, int mouseY) {
        Rect view = layout.library;
        clip(g, view);
        if (libraryRows.cards().isEmpty()) hint(g, view, ComponentHelper.translatable("ui.fangsu.common.no_results"), MUTED);
        for (TrainSelectionLayout.Card card : libraryRows.cards()) {
            Rect box = card.bounds().shiftY(-libraryScroll);
            if (!box.intersectsVertically(view)) continue;
            Rect header = card.title().shiftY(-libraryScroll);
            TrainCatalog.Variant variant = card.variant();
            fill(g, box, ROW);
            g.fill(box.left() + 2, box.top() + 3, box.left() + 5, box.bottom() - 3, color(variant.allCars().get(0)));
            if (view.contains(mouseX, mouseY) && header.contains(mouseX, mouseY)) fill(g, header, HOVER);
            drawScrollingText(g, view, variant.name(), header.left() + 3, header.top() + 2, header.width() - 14, TEXT);
            g.drawString(font, "+", header.right() - 9, header.top() + 2, ACCENT, false);
            drawScrollingText(g, view, variant.subtitle(), header.left() + 3, header.top() + 12, header.width() - 5, MUTED);
            for (TrainSelectionLayout.CarButton button : card.buttons()) {
                Rect bounds = button.bounds().shiftY(-libraryScroll);
                boolean hovered = view.contains(mouseX, mouseY) && bounds.contains(mouseX, mouseY);
                drawCar(g, view, bounds, button.car().role(), false, hovered);
            }
        }
        g.disableScissor();
        drawScrollbar(g, ScrollPane.LIBRARY);
    }

    private void drawTags(GraphicContext g, int mouseX, int mouseY) {
        Rect view = layout.library;
        clip(g, view);
        if (tagRows.isEmpty()) hint(g, view, text("no_tags"), MUTED);
        int space = font.width(" ");
        int categoryMarkerWidth = Math.max(font.width("−"), font.width("+")) + space;
        int checkboxWidth = Math.max(font.width("[✓]"), font.width("[ ]")) + space;
        for (int i = 0; i < tagRows.size(); i++) {
            Rect bounds = layout.tagRow(i, tagScroll);
            if (!bounds.intersectsVertically(view)) continue;
            TagRow row = tagRows.get(i);
            boolean header = row.value == null;
            boolean active = !header && filters.getOrDefault(row.category, Set.of()).contains(row.value);
            boolean hovered = view.contains(mouseX, mouseY) && bounds.contains(mouseX, mouseY);
            if (header || active || hovered) fill(g, bounds, active || hovered ? HOVER : ROW);
            String marker = header ? (expandedTags.contains(row.category) ? "−" : "+") : (active ? "[✓]" : "[ ]");
            String label = header ? tagName(row.category) : row.value;
            int markerX = bounds.left() + (header ? 4 : 9);
            int labelX = markerX + (header ? categoryMarkerWidth : checkboxWidth);
            int y = bounds.top() + 3;
            int color = active ? ACCENT : TEXT;
            g.drawString(font, marker, markerX, y, color, false);
            drawScrollingText(g, view, label, labelX, y, bounds.right() - labelX - 2, color);
        }
        g.disableScissor();
        drawScrollbar(g, ScrollPane.TAGS);
    }

    private void drawFormation(GraphicContext g, int mouseX, int mouseY) {
        Rect view = layout.formation;
        clip(g, view);
        if (draft.isEmpty()) hint(g, view, text("empty_formation"), MUTED);
        int first = Math.max(0, (formationScroll - 4) / TrainSelectionLayout.FORMATION_ROW);
        for (int i = first; i < draft.size(); i++) {
            Rect row = layout.formationRow(i, formationScroll);
            if (row.top() >= view.bottom()) break;
            VehicleCar car = draft.get(i);
            TrainCatalog.Car resource = catalog.find(car.getVehicleId());
            boolean hovered = view.contains(mouseX, mouseY) && row.contains(mouseX, mouseY);
            fill(g, row, hovered ? HOVER : ROW);
            g.fill(row.left() + 2, row.top() + 3, row.left() + 5, row.bottom() - 3, resource == null ? MUTED : color(resource));
            TrainCatalog.Role role = resource == null ? TrainCatalog.classify(car.getVehicleId()) : resource.role();
            drawCar(g, view, new Rect(row.left() + 8, row.top() + 2, row.left() + 33, row.bottom() - 2), role, true, hovered);
            Rect remove = row.removeButton();
            int labelX = row.left() + 36;
            String name = resource == null ? text("missing_resource").getString() : resource.name();
            drawScrollingText(g, view, name, labelX, row.top() + 4, remove.left() - labelX - 2, TEXT);
            drawScrollingText(g, view, car.getVehicleId(), labelX, row.top() + 15, remove.left() - labelX - 2, MUTED);
            if (hovered && remove.contains(mouseX, mouseY)) fill(g, remove, PANE);
            g.drawCenteredString(font, "×", remove.centerX(), remove.top() + 3, hovered ? TEXT : MUTED);
        }
        if (draggingCar && view.contains(mouseX, mouseY)) {
            int target = layout.insertion(mouseY, draft.size(), formationScroll);
            int y = layout.formationRow(target, formationScroll).top() - 2;
            y = Math.max(view.top(), Math.min(view.bottom() - 2, y));
            g.fill(view.left() + 3, y, view.right() - 6, y + 2, ACCENT);
        }
        g.disableScissor();
        drawScrollbar(g, ScrollPane.FORMATION);
    }

    private void drawDetails(GraphicContext g) {
        Rect view = layout.details;
        clip(g, view);
        if (detailId == null) hint(g, view, text("hover_hint"), MUTED);
        int y = view.top() + 5 - detailsScroll;
        for (int index = 0; index < detailsLines.size(); index++) {
            if (y >= view.bottom()) break;
            if (y + font.lineHeight >= view.top()) {
                g.drawString(font, detailsLines.get(index), view.left() + 5, y, index == 0 ? TEXT : MUTED, false);
            }
            y += font.lineHeight + 2;
        }
        g.disableScissor();
        drawScrollbar(g, ScrollPane.DETAILS);
    }

    private void drawDrag(GraphicContext g, int mouseX, int mouseY) {
        TrainCatalog.Car resource = catalog.find(drag.car.getVehicleId());
        TrainCatalog.Role role = resource == null ? TrainCatalog.classify(drag.car.getVehicleId()) : resource.role();
        int x = Math.max(0, Math.min(width - 42, mouseX + 7));
        int y = Math.max(0, Math.min(height - 23, mouseY + 6));
        drawCar(g, null, new Rect(x, y, x + 42, y + 23), role, false, true);
    }

    /** 逐行或逐列裁出轮廓，左栏横放，中栏沿编组方向竖放。 */
    private void drawCar(GraphicContext g, Rect viewport, Rect bounds, TrainCatalog.Role role, boolean vertical, boolean hovered) {
        int left = bounds.left() + 2;
        int top = bounds.top() + 2;
        int width = bounds.width() - 4;
        int height = bounds.height() - 4;
        if (width < 3 || height < 3) return;
        int edge = hovered ? ACCENT : 0xFFC4CDD8;
        int interior = hovered ? HOVER : PANE;
        boolean front = role.shape() == TrainCatalog.Shape.FRONT || role.shape() == TrainCatalog.Shape.DOUBLE;
        boolean rear = role.shape() == TrainCatalog.Shape.REAR || role.shape() == TrainCatalog.Shape.DOUBLE;
        int slope = Math.min(7, (vertical ? height : width) / 4);
        int lines = vertical ? width : height;
        for (int line = 0; line < lines; line++) {
            int cut = slope * (vertical ? line : lines - 1 - line) / (lines - 1);
            int start = front ? cut : 0;
            int end = (vertical ? height : width) - (rear ? cut : 0);
            if (vertical) g.fill(left + line, top + start, left + line + 1, top + end, edge);
            else g.fill(left + start, top + line, left + end, top + line + 1, edge);
            if (line == 0 || line == lines - 1 || end - start <= 2) continue;
            if (vertical) g.fill(left + line, top + start + 1, left + line + 1, top + end - 1, interior);
            else g.fill(left + start + 1, top + line, left + end - 1, top + line + 1, interior);
        }
        if (!role.label().isEmpty()) {
            int labelWidth = Math.min(font.width(role.label()), width - 4);
            drawScrollingText(g, viewport, role.label(), bounds.centerX() - labelWidth / 2,
                    top + (height - font.lineHeight) / 2, labelWidth, TEXT);
        }
    }

    private void updateHover(int mouseX, int mouseY) {
        TrainSelectionLayout.CarButton source = libraryCar(mouseX, mouseY);
        int index = formationIndex(mouseX, mouseY);
        if (source != null) {
            setDetails(source.car().id(), source.car().resource().getLength());
        } else if (index >= 0) {
            VehicleCar car = draft.get(index);
            setDetails(car.getVehicleId(), car.getLength());
        } else if (layout.library.contains(mouseX, mouseY) || layout.formation.contains(mouseX, mouseY)) {
            setDetails(null, 0);
        }
        if (wikiRequest != null && wikiText == null && wikiRequest.isDone()) {
            wikiText = wikiRequest.getNow("");
            rebuildDetails();
        }
    }

    private void setDetails(String id, double length) {
        if (Objects.equals(detailId, id) && detailLength == length) return;
        detailId = id;
        detailLength = length;
        detailsScroll = 0;
        wikiText = null;
        wikiRequest = null;
        TrainCatalog.Car car = id == null ? null : catalog.find(id);
        if (car != null) {
            String article = car.resource().getWikipediaArticle();
            if (!article.isBlank() && car.resource().getDescription().getString().isBlank()) {
                wikiRequest = TrainDescriptionCache.request(article);
                if (wikiRequest.isDone()) wikiText = wikiRequest.getNow("");
            }
        }
        rebuildDetails();
    }

    private void rebuildDetails() {
        List<FormattedCharSequence> lines = new ArrayList<>();
        if (detailId != null) {
            TrainCatalog.Car car = catalog.find(detailId);
            int width = Math.max(1, layout.details.width() - 13);
            if (car != null) lines.addAll(font.split(FormattedText.of(car.name()), width));
            lines.addAll(font.split(FormattedText.of(detailId), width));
            lines.addAll(font.split(text("length", meters(detailLength)), width));
            if (car == null) {
                lines.addAll(font.split(text("missing_resource"), width));
            } else {
                car.tags().forEach((key, values) -> lines.addAll(font.split(FormattedText.of(tagName(key) + " · " + String.join(", ", values)), width)));
                String description = car.resource().getDescription().getString();
                if (!description.isBlank()) {
                    appendDescription(lines, description, width);
                } else if (wikiRequest != null) {
                    if (wikiText == null) lines.addAll(font.split(text("wiki_loading"), width));
                    else if (wikiText.isBlank()) lines.addAll(font.split(text("wiki_unavailable"), width));
                    else appendDescription(lines, wikiText, width);
                }
            }
        }
        detailsLines = lines;
        detailsScroll = TrainSelectionLayout.clampScroll(detailsScroll, layout.details, detailsRowsHeight());
    }

    private void appendDescription(List<FormattedCharSequence> lines, String description, int width) {
        lines.add(FormattedCharSequence.EMPTY);
        for (String line : description.split("[|\n]")) {
            lines.addAll(font.split(FormattedText.of(line), width));
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (button != 0) return false;
        if (clickScrollbar(x, y)) return true;
        if (showingFilters && layout.library.contains(x, y)) return clickTag(x, y);
        TrainSelectionLayout.CarButton source = libraryCar(x, y);
        if (source != null) {
            drag = new Drag(source.car().createCar(), -1, x, y);
            return true;
        }
        if (!showingFilters && layout.library.contains(x, y)) {
            for (TrainSelectionLayout.Card card : libraryRows.cards()) {
                if (card.title().shiftY(-libraryScroll).contains(x, y)) {
                    addFormation(card.variant());
                    return true;
                }
            }
        }
        int index = formationIndex(x, y);
        if (index < 0) return false;
        if (layout.formationRow(index, formationScroll).removeButton().contains(x, y)) {
            List<VehicleCar> candidate = new ArrayList<>(draft);
            candidate.remove(index);
            applyEdit(candidate);
        } else {
            drag = new Drag(draft.get(index), index, x, y);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && scrollDrag != null) {
            ScrollPane pane = scrollDrag.pane;
            TrainSelectionLayout.Scrollbar bar = scrollbar(pane);
            if (bar != null) setScroll(pane, bar.scrollAt(y, scrollDrag.grabOffset));
            return true;
        }
        if (button != 0 || drag == null) return super.mouseDragged(x, y, button, dx, dy);
        pointerX = x;
        pointerY = y;
        draggingCar |= Math.abs(x - drag.x) >= 4 || Math.abs(y - drag.y) >= 4;
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && scrollDrag != null) {
            scrollDrag = null;
            return true;
        }
        if (button != 0 || drag == null) return super.mouseReleased(x, y, button);
        Drag active = drag;
        boolean moved = draggingCar || Math.abs(x - active.x) >= 4 || Math.abs(y - active.y) >= 4;
        drag = null;
        draggingCar = false;
        if (moved && layout.formation.contains(x, y)) {
            int index = layout.insertion(y, draft.size(), formationScroll);
            moveCar(active, index);
        } else if (!moved && active.index < 0) {
            TrainSelectionLayout.CarButton target = libraryCar(x, y);
            if (target != null && target.car().id().equals(active.car.getVehicleId())) moveCar(active, draft.size());
        }
        return true;
    }

    //#if MC_VERSION < 12003
    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
    //#else
    //$$ @Override
    //$$ public boolean mouseScrolled(double x, double y, double horizontalAmount, double delta) {
    //#endif
        ScrollPane pane = paneAt(x, y);
        if (pane != null) {
            if (scrollDrag == null) setScroll(pane, scroll(pane) - (int) Math.round(delta * 24));
            return true;
        }
        //#if MC_VERSION < 12003
        return super.mouseScrolled(x, y, delta);
        //#else
        //$$ return super.mouseScrolled(x, y, horizontalAmount, delta);
        //#endif
    }

    private TrainSelectionLayout.CarButton libraryCar(double x, double y) {
        if (showingFilters || !layout.library.contains(x, y)) return null;
        double logicalY = y + libraryScroll;
        for (TrainSelectionLayout.Card card : libraryRows.cards()) {
            if (!card.bounds().contains(x, logicalY)) continue;
            for (TrainSelectionLayout.CarButton button : card.buttons()) {
                if (button.bounds().contains(x, logicalY)) return button;
            }
        }
        return null;
    }

    private int formationIndex(double x, double y) {
        if (!layout.formation.contains(x, y)) return -1;
        int local = (int) (y - layout.formation.top()) - 4 + formationScroll;
        if (local < 0) return -1;
        int index = local / TrainSelectionLayout.FORMATION_ROW;
        return index < draft.size() && layout.formationRow(index, formationScroll).contains(x, y) ? index : -1;
    }

    private boolean clickTag(double x, double y) {
        int local = (int) (y - layout.library.top()) - 4 + tagScroll;
        if (local < 0) return false;
        int index = local / TrainSelectionLayout.TAG_ROW;
        if (index >= tagRows.size() || !layout.tagRow(index, tagScroll).contains(x, y)) return false;
        TagRow tag = tagRows.get(index);
        if (tag.value == null) {
            if (!expandedTags.add(tag.category)) expandedTags.remove(tag.category);
            textScrollStart = System.nanoTime();
            rebuildTags();
        } else {
            Set<String> values = filters.computeIfAbsent(tag.category, ignored -> new HashSet<>());
            if (!values.add(tag.value)) values.remove(tag.value);
            if (values.isEmpty()) filters.remove(tag.category);
            rebuildLibrary(true);
            refreshButtons();
        }
        return true;
    }

    private void moveCar(Drag source, int index) {
        List<VehicleCar> candidate = new ArrayList<>(draft);
        if (source.index >= 0) {
            candidate.remove(source.index);
            if (source.index < index) index--;
        }
        candidate.add(index, source.car);
        boolean appended = source.index < 0 && index == draft.size();
        if (applyEdit(candidate) && appended) {
            formationScroll = formationRowsHeight();
            clampFormationScroll();
        }
    }

    private void addFormation(TrainCatalog.Variant variant) {
        TrainFormation.Result result = formation.build(variant, catalog);
        if (result.error() != null) {
            notice = text(result.error(), result.argument());
        } else if (applyEdit(result.cars())) {
            formationScroll = 0;
        }
    }

    private void duplicate() {
        List<VehicleCar> expanded = formation.duplicate(draft);
        if (expanded.size() == draft.size()) notice = text("does_not_fit");
        else applyEdit(expanded);
    }

    private boolean applyEdit(List<VehicleCar> cars) {
        if (!formation.fits(cars)) {
            notice = text("does_not_fit");
            return false;
        }
        draft.clear();
        draft.addAll(cars);
        notice = null;
        clampFormationScroll();
        refreshButtons();
        return true;
    }

    private void save() {
        if (sameCars(draft, original)) {
            onClose();
            return;
        }
        MinecraftClientData dashboard = MinecraftClientData.getDashboardInstance();
        Siding current = dashboard.sidingIdMap.get(siding.getId());
        if (current == null || !sameCars(original, current.getVehicleCars())) {
            notice = text("formation_changed");
            return;
        }
        if (!formation.fits(draft) || (current != siding && !new TrainFormation(current).fits(draft))) {
            notice = text("does_not_fit");
            return;
        }
        ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>(draft);
        siding.setVehicleCars(cars);
        if (current != siding) current.setVehicleCars(cars);
        InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(new UpdateDataRequest(dashboard).addSiding(current)));
        onClose();
    }

    private static boolean sameCars(List<VehicleCar> first, List<VehicleCar> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            VehicleCar a = first.get(i);
            VehicleCar b = second.get(i);
            if (a == b) continue;
            if (!a.getVehicleId().equals(b.getVehicleId()) || a.getLength() != b.getLength() || a.getWidth() != b.getWidth()
                    || a.getBogie1Position() != b.getBogie1Position() || a.getBogie2Position() != b.getBogie2Position()
                    || a.getTotalLength(true, false) != b.getTotalLength(true, false)
                    || a.getTotalLength(false, true) != b.getTotalLength(false, true)) return false;
        }
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void clampFormationScroll() {
        formationScroll = TrainSelectionLayout.clampScroll(formationScroll, layout.formation, formationRowsHeight());
    }

    private int formationRowsHeight() { return 8 + draft.size() * TrainSelectionLayout.FORMATION_ROW; }
    private int tagRowsHeight() { return 8 + tagRows.size() * TrainSelectionLayout.TAG_ROW; }
    private int detailsRowsHeight() { return 10 + detailsLines.size() * (font.lineHeight + 2); }

    private void hint(GraphicContext g, Rect view, Component hint, int color) {
        int y = view.top() + 5;
        for (FormattedCharSequence line : font.split(hint, Math.max(1, view.width() - 13))) {
            g.drawString(font, line, view.left() + 5, y, color, false);
            y += font.lineHeight + 2;
        }
    }

    private void drawScrollingText(GraphicContext g, Rect viewport, String text, int x, int y, int width, int color) {
        if (width <= 0) return;
        int textWidth = font.width(text);
        if (textWidth <= width) {
            g.drawString(font, text, x, y, color, false);
            return;
        }
        int clipTop = viewport == null ? y : Math.max(y, viewport.top());
        int clipBottom = y + font.lineHeight + 1;
        if (viewport != null) clipBottom = Math.min(clipBottom, viewport.bottom());
        if (clipTop >= clipBottom) return;

        int period = font.width(text + " ");
        long scrollDuration = period * TEXT_SCROLL_MILLIS_PER_PIXEL;
        long phase = textScrollMillis % (scrollDuration + TEXT_SCROLL_PAUSE_MILLIS);
        int offset = phase < scrollDuration ? (int) (phase / TEXT_SCROLL_MILLIS_PER_PIXEL) : 0;
        g.enableScissor(x, clipTop, x + width, clipBottom);
        g.drawString(font, text, x - offset, y, color, false);
        g.drawString(font, text, x - offset + period, y, color, false);
        g.disableScissor();
        //#if MC_VERSION < 12000
        //$$ // PoseStack 路径没有裁剪栈，画完文字后恢复整栏裁剪。
        //$$ if (viewport != null) clip(g, viewport);
        //#endif
    }

    private static void fill(GraphicContext g, Rect bounds, int color) {
        g.fill(bounds.left(), bounds.top(), bounds.right(), bounds.bottom(), color);
    }

    private static void clip(GraphicContext g, Rect bounds) {
        g.enableScissor(bounds.left(), bounds.top(), bounds.right() - 5, bounds.bottom());
    }

    private ScrollPane paneAt(double x, double y) {
        if (layout.library.contains(x, y)) return showingFilters ? ScrollPane.TAGS : ScrollPane.LIBRARY;
        if (layout.formation.contains(x, y)) return ScrollPane.FORMATION;
        return layout.details.contains(x, y) ? ScrollPane.DETAILS : null;
    }

    private int scroll(ScrollPane pane) {
        return switch (pane) {
            case LIBRARY -> libraryScroll;
            case TAGS -> tagScroll;
            case FORMATION -> formationScroll;
            case DETAILS -> detailsScroll;
        };
    }

    private void setScroll(ScrollPane pane, int offset) {
        TrainSelectionLayout.Scrollbar bar = scrollbar(pane);
        int clamped = bar == null ? 0 : Math.max(0, Math.min(offset, bar.range()));
        switch (pane) {
            case LIBRARY -> libraryScroll = clamped;
            case TAGS -> tagScroll = clamped;
            case FORMATION -> formationScroll = clamped;
            case DETAILS -> detailsScroll = clamped;
        }
    }

    private TrainSelectionLayout.Scrollbar scrollbar(ScrollPane pane) {
        return switch (pane) {
            case LIBRARY -> TrainSelectionLayout.scrollbar(layout.library, libraryRows.height());
            case TAGS -> TrainSelectionLayout.scrollbar(layout.library, tagRowsHeight());
            case FORMATION -> TrainSelectionLayout.scrollbar(layout.formation, formationRowsHeight());
            case DETAILS -> TrainSelectionLayout.scrollbar(layout.details, detailsRowsHeight());
        };
    }

    private boolean clickScrollbar(double x, double y) {
        ScrollPane pane = paneAt(x, y);
        if (pane == null) return false;
        TrainSelectionLayout.Scrollbar bar = scrollbar(pane);
        if (bar == null || !bar.track().contains(x, y)) return false;
        Rect thumb = bar.thumb(scroll(pane));
        if (thumb.contains(x, y)) {
            scrollDrag = new ScrollDrag(pane, bar.grabOffset(y, scroll(pane)));
        } else {
            int direction = y < thumb.top() ? -1 : 1;
            setScroll(pane, scroll(pane) + direction * Math.max(1, bar.track().height() - 12));
        }
        return true;
    }

    private void drawScrollbar(GraphicContext g, ScrollPane pane) {
        TrainSelectionLayout.Scrollbar bar = scrollbar(pane);
        if (bar == null) return;
        Rect track = bar.track();
        Rect thumb = bar.thumb(scroll(pane));
        g.fill(track.left() + 1, track.top(), track.right() - 1, track.bottom(), ROW);
        boolean highlighted = thumb.contains(pointerX, pointerY) || (scrollDrag != null && scrollDrag.pane == pane);
        g.fill(thumb.left() + 1, thumb.top(), thumb.right() - 1, thumb.bottom(), highlighted ? ACCENT : MUTED);
    }

    private static int color(TrainCatalog.Car car) { return car.resource().getColor() | 0xFF000000; }
    private static String meters(double length) { return String.format(Locale.ROOT, "%.1f", length); }
    private static Component text(String key, Object... args) { return ComponentHelper.translatable(LANG + key, args); }

    private static String tagName(String category) {
        return switch (category) {
            case "family", "type", "doors", "iso_3166", "brand", "material" -> text("tag." + category).getString();
            default -> category;
        };
    }

    private record TagRow(String category, String value) {}
    private record Drag(VehicleCar car, int index, double x, double y) {}
    private record ScrollDrag(ScrollPane pane, double grabOffset) {}
    private enum ScrollPane { LIBRARY, TAGS, FORMATION, DETAILS }
}
