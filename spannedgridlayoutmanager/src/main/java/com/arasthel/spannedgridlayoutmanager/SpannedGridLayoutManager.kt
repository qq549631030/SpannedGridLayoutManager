/*
 * Copyright © 2017 Jorge Martín Espinosa
 */

package com.arasthel.spannedgridlayoutmanager

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import android.util.SparseArray
import android.view.View
import androidx.recyclerview.widget.*

/**
 * A [LinearLayoutManager] which layouts and orders its views
 * based on width and height spans.
 *
 * @param orientation Whether the views will be layouted and scrolled in vertical or horizontal
 * @param _verticalSpans How many spans does the layout have per row
 */
open class SpannedGridLayoutManager(
    context: Context,
    @RecyclerView.Orientation orientation: Int,
    _verticalSpans: Int,
    _horizontalSpans: Int
) : LinearLayoutManager(context, orientation, false) {

    //==============================================================================================
    //  ~ Orientation & Direction enums
    //==============================================================================================

    /**
     * Direction of scroll for layouting process
     * <li>START</li>
     * <li>END</li>
     */
    enum class Direction {
        START, END
    }

    //==============================================================================================
    //  ~ Properties
    //==============================================================================================

    val decoratedWidth: Int
        get() = width - paddingLeft - paddingRight
    val decoratedHeight: Int
        get() = height - paddingTop - paddingBottom

    /**
     * Space occupied by each span
     */
    val itemWidth: Int get() = if (customWidth > -1) customWidth else (decoratedWidth / horizontalSpanCount)
    val itemHeight: Int get() = if (customHeight > -1) customHeight else (decoratedHeight / verticalSpanCount)

    val orientationHelper by lazy {
        if (orientation == RecyclerView.VERTICAL) OrientationHelper.createVerticalHelper(this)
        else OrientationHelper.createHorizontalHelper(this)
    }

    var customWidth = -1
        set(value) {
            field = value
            requestLayout()
        }
    var customHeight = -1
        set(value) {
            field = value
            requestLayout()
        }

    var verticalSpanCount: Int = _verticalSpans
        set(value) {
            if (field == value) {
                return
            }

            field = value
            require(verticalSpanCount >= 1) {
                ("Span count should be at least 1. Provided "
                        + verticalSpanCount)
            }
            spanSizeLookup?.invalidateCache()
            requestLayout()
        }

    var horizontalSpanCount: Int = _horizontalSpans
        set(value) {
            if (field == value) {
                return
            }

            field = value
            require(horizontalSpanCount >= 1) {
                ("Span count should be at least 1. Provided "
                        + horizontalSpanCount)
            }
            spanSizeLookup?.invalidateCache()
            requestLayout()
        }

    var scroll = 0

    /**
     * Helper get free rects to place views
     */
    protected lateinit var rectsHelper: RectsHelper

    /**
     * First visible position in layout - changes with recycling
     */
    open val firstVisiblePosition: Int get() {
        if (childCount == 0) { return -1 }
        return getPosition(getChildAt(0)!!)
    }

    open val firstCompletelyVisiblePosition: Int
        get() {
            if (childCount == 0) return -1

            for (i in 0 until childCount) {
                val child = getChildAt(i) ?: continue
                val rect = Rect().apply { getDecoratedBoundsWithMargins(child, this) }

                if (orientation == VERTICAL) {
                    if (rect.top >= 0 && rect.bottom <= size) return i
                } else {
                    if (rect.left >= 0 && rect.right <= size) return i
                }
            }

            return -1
        }

    /**
     * Last visible position in layout - changes with recycling
     */
    open val lastVisiblePosition: Int get() {
        if (childCount == 0) { return -1 }
        return getPosition(getChildAt(childCount - 1)!!)
    }

    open val lastCompletelyVisiblePosition: Int
        get() {
            if (childCount == 0) return -1

            for (i in childCount - 1 downTo 0) {
                val child = getChildAt(i) ?: continue
                val rect = Rect().apply { getDecoratedBoundsWithMargins(child, this) }

                if (orientation == VERTICAL) {
                    if (rect.top >= 0 && rect.bottom <= size) return i
                } else {
                    if (rect.left >= 0 && rect.right <= size) return i
                }
            }

            return -1
        }

    /**
     * Start of the layout. Should be [getPaddingEndForOrientation] + first visible item top
     */
    protected var layoutStart = 0
    /**
     * End of the layout. Should be [layoutStart] + last visible item bottom + [getPaddingEndForOrientation]
     */
    protected var layoutEnd = 0

    /**
     * Total length of the layout depending on current orientation
     */
    val size: Int get() = if (orientation == RecyclerView.VERTICAL) decoratedHeight else decoratedWidth

    /**
     * Cache of rects for layouted views
     */
    protected val childFrames = mutableMapOf<Int, Rect>()

    /**
     * Temporary variable to store wanted scroll by [scrollToPosition]
     */
    protected var pendingScrollToPosition: Int? = null

    /**
     * Whether item order will be kept along re-creations of this LayoutManager with different
     * configurations of not. Default is false. Only set to true if this condition is met.
     * Otherwise, scroll bugs will happen.
     */
    var itemOrderIsStable = false

    /**
     * Provides SpanSize values for the LayoutManager. Otherwise they will all be (1, 1).
     */
    var spanSizeLookup: SpanSizeLookup? = null
        set(newValue) {
            field = newValue
            // If the SpanSizeLookup changes, the views need a whole re-layout
            requestLayout()
        }

    protected var recyclerView: RecyclerView? = null

    /**
     * SpanSize provider for this LayoutManager.
     * SpanSizes can be cached to improve efficiency.
     */
    open class SpanSizeLookup(
            /** Used to provide an SpanSize for each item. */
            var lookupFunction: ((Int) -> SpanSize)? = null
    ) {
        
        private var cache = SparseArray<SpanSize>()

        /**
         * Enable SpanSize caching. Can be used to improve performance if calculating the SpanSize
         * for items is a complex process.
         */
        var usesCache = false

        /**
         * Returns an SpanSize for the provided position.
         * @param position Adapter position of the item
         * @return An SpanSize, either provided by the user or the default one.
         */
        fun getSpanSize(position: Int): SpanSize {
            if (usesCache) {
                val cachedValue = cache[position]
                if (cachedValue != null) return cachedValue
                
                val value = getSpanSizeFromFunction(position)
                cache.put(position, value)
                return value
            } else {
                return getSpanSizeFromFunction(position)
            }
        }
        
        private fun getSpanSizeFromFunction(position: Int): SpanSize {
            return lookupFunction?.invoke(position) ?: getDefaultSpanSize()
        }
        
        protected open fun getDefaultSpanSize(): SpanSize {
            return SpanSize(1, 1)
        }

        fun invalidateCache() {
            cache.clear()
        }
    }

    //==============================================================================================
    //  ~ Initializer
    //==============================================================================================

    init {
        if (_verticalSpans < 1) {
            throw InvalidMaxSpansException(_verticalSpans)
        }

        if (_horizontalSpans < 1) {
            throw InvalidMaxSpansException(_horizontalSpans)
        }
    }

    //==============================================================================================
    //  ~ Override parent
    //==============================================================================================

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams {
        return RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onAttachedToWindow(view: RecyclerView?) {
        recyclerView = view
        super.onAttachedToWindow(view)
    }

    override fun onDetachedFromWindow(view: RecyclerView?, recycler: RecyclerView.Recycler?) {
        recyclerView = null
        super.onDetachedFromWindow(view, recycler)
    }

    //==============================================================================================
    //  ~ View layouting methods
    //==============================================================================================

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        rectsHelper = RectsHelper(this, orientation)

        layoutStart = getPaddingStartForOrientation()

        layoutEnd = if (scroll != 0) {
            (scroll - layoutStart)
        } else {
            getPaddingEndForOrientation()
        }

        // Clear cache, since layout may change
        childFrames.clear()

        // If there were any views, detach them so they can be recycled
        detachAndScrapAttachedViews(recycler)

        val start = System.currentTimeMillis()

        for (i in 0 until state.itemCount) {
            val spanSize = spanSizeLookup?.getSpanSize(i) ?: SpanSize(1, 1)
            val childRect = rectsHelper.findRect(i, spanSize)
            rectsHelper.pushRect(i, childRect)
        }

        if (DEBUG) {
            val elapsed = System.currentTimeMillis() - start
            debugLog("Elapsed time: $elapsed ms")
        }

        // Restore scroll position based on first visible view
        val pendingScrollToPosition = pendingScrollToPosition
        if (itemCount != 0 && pendingScrollToPosition != null) {
            try {
                val child = makeView(pendingScrollToPosition, Direction.END, recycler)
                scroll = getPaddingStartForOrientation() + getChildStart(child)
            } catch (e: IndexOutOfBoundsException) {
                //pendingScrollPosition is not in dataset bounds
            }

            this.pendingScrollToPosition = null
        }

        // Fill from start to visible end
        fillGap(Direction.END, recycler, state)

        recycleChildrenOutOfBounds(Direction.END, recycler)

        val (childEnd, _) = getGreatestChildEnd()

        // Check if after changes in layout we aren't out of its bounds
        val overScroll = size - childEnd - getPaddingEndForOrientation()
        val isLastItemInScreen = (0 until childCount).map { getPosition(getChildAt(it)!!) }.contains(itemCount - 1)
        val allItemsInScreen = itemCount == 0 || (firstVisiblePosition == 0 && isLastItemInScreen)
        if (!allItemsInScreen && overScroll > 0) {
            // If we are, fix it
            scrollBy(overScroll, state, childEnd)

            if (overScroll > 0) {
                fillBefore(recycler)
            } else {
                fillAfter(recycler)
            }
        }
    }

    /**
     * Measure child view using [RectsHelper]
     */
    protected open fun measureChild(position: Int, view: View) {
        val freeRectsHelper = this.rectsHelper
        val spanSize = spanSizeLookup?.getSpanSize(position) ?: SpanSize(1, 1)
        val usedSpan = if (orientation == RecyclerView.HORIZONTAL) spanSize.height else spanSize.width

        if (usedSpan > getSpanCountForOrientation() || usedSpan < 1) {
            throw InvalidSpanSizeException(errorSize = usedSpan, maxSpanSize = getSpanCountForOrientation())
        }

        // This rect contains just the row and column number - i.e.: [0, 0, 1, 1]
        val rect = freeRectsHelper.findRect(position, spanSize)

        // Multiply the rect for item width and height to get positions
        val left = rect.left * itemWidth
        val right = rect.right * itemWidth
        val top = rect.top * itemHeight
        val bottom = rect.bottom * itemHeight

        val insetsRect = Rect()
        calculateItemDecorationsForChild(view, insetsRect)

        // Measure child
        val width = right - left - insetsRect.left - insetsRect.right
        val height = bottom - top - insetsRect.top - insetsRect.bottom
        val layoutParams = view.layoutParams
        layoutParams.width = width
        layoutParams.height = height
        view.layoutParams = layoutParams
        measureChildWithMargins(view, width, height)

        // Cache rect
        childFrames[position] = Rect(left, top, right, bottom)
    }

    /**
     * Layout child once it's measured and its position cached
     */
    protected open fun layoutChild(position: Int, view: View) {
        val frame = childFrames[position]

        if (frame != null) {
            val startPadding = getPaddingStartForOrientation()

            if (orientation == RecyclerView.VERTICAL) {
                layoutDecorated(view,
                        frame.left + paddingLeft,
                        frame.top - scroll + startPadding,
                        frame.right + paddingLeft,
                        frame.bottom - scroll + startPadding)
            } else {
                layoutDecorated(view,
                        frame.left - scroll + startPadding,
                        frame.top + paddingTop,
                        frame.right - scroll + startPadding,
                        frame.bottom + paddingTop)
            }
        }

        // A new child was layouted, layout edges change
        updateEdgesWithNewChild(view)
    }

    /**
     * Ask the recycler for a view, measure and layout it and add it to the layout
     */
    protected open fun makeAndAddView(position: Int, direction: Direction, recycler: RecyclerView.Recycler): View {
        val view = makeView(position, direction, recycler)

        if (direction == Direction.END) {
            addView(view)
        } else {
            addView(view, 0)
        }

        return view
    }

    protected open fun makeView(position: Int, direction: Direction, recycler: RecyclerView.Recycler): View {
        val view = recycler.getViewForPosition(position)
        measureChild(position, view)
        layoutChild(position, view)

        return view
    }

    /**
     * A new view was added, update layout edges if needed
     */
    protected open fun updateEdgesWithNewChild(view: View) {
        val childStart = getChildStart(view) + scroll + getPaddingStartForOrientation()

        if (childStart < layoutStart) {
            layoutStart = childStart
        }

        val newLayoutEnd = childStart + getItemSizeForOrientation()

        if (newLayoutEnd > layoutEnd) {
            layoutEnd = newLayoutEnd
        }
    }

    //==============================================================================================
    //  ~ Recycling methods
    //==============================================================================================

    /**
     * Recycle any views that are out of bounds
     */
    protected open fun recycleChildrenOutOfBounds(direction: Direction, recycler: RecyclerView.Recycler) {
        if (direction == Direction.END) {
            recycleChildrenFromStart(direction, recycler)
        } else {
            recycleChildrenFromEnd(direction, recycler)
        }
    }

    /**
     * Recycle views from start to first visible item
     */
    protected open fun recycleChildrenFromStart(direction: Direction, recycler: RecyclerView.Recycler) {
        val childCount = childCount
        val start = getPaddingStartForOrientation()

        val toDetach = mutableListOf<View>()

        for (i in 0 until childCount) {
            val child = getChildAt(i)!!
            val childEnd = getChildEnd(child)

            if (childEnd < start) {
                toDetach.add(child)
            }
        }

        for (child in toDetach) {
            removeAndRecycleView(child, recycler)
            updateEdgesWithRemovedChild(child, direction)
        }
    }

    /**
     * Recycle views from end to last visible item
     */
    protected open fun recycleChildrenFromEnd(direction: Direction, recycler: RecyclerView.Recycler) {
        val childCount = childCount
        val end = size + getPaddingEndForOrientation()

        val toDetach = mutableListOf<View>()

        for (i in (0 until childCount).reversed()) {
            val child = getChildAt(i)!!
            val childStart = getChildStart(child)

            if (childStart > end) {
                toDetach.add(child)
            }
        }

        for (child in toDetach) {
            removeAndRecycleView(child, recycler)
            updateEdgesWithRemovedChild(child, direction)
        }
    }

    /**
     * Update layout edges when views are recycled
     */
    protected open fun updateEdgesWithRemovedChild(view: View, direction: Direction) {
        val childStart = getChildStart(view) + scroll
        val childEnd = getChildEnd(view) + scroll

        if (direction == Direction.END) { // Removed from start
            layoutStart = getPaddingStartForOrientation() + childEnd
        } else if (direction == Direction.START) { // Removed from end
            layoutEnd = getPaddingStartForOrientation() + childStart
        }
    }

    //==============================================================================================
    //  ~ Scroll methods
    //==============================================================================================

    override fun computeVerticalScrollOffset(state: RecyclerView.State): Int {
        return computeScrollOffset()
    }

    override fun computeHorizontalScrollOffset(state: RecyclerView.State): Int {
        return computeScrollOffset()
    }

    private fun computeScrollOffset(): Int {
        return if (childCount == 0) 0 else firstVisiblePosition
    }

    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int {
        return childCount
    }

    override fun computeHorizontalScrollExtent(state: RecyclerView.State): Int {
        return childCount
    }

    override fun computeVerticalScrollRange(state: RecyclerView.State): Int {
        return state.itemCount
    }

    override fun computeHorizontalScrollRange(state: RecyclerView.State): Int {
        return state.itemCount
    }

    override fun canScrollVertically(): Boolean {
        return orientation == RecyclerView.VERTICAL
    }

    override fun canScrollHorizontally(): Boolean {
        return orientation == RecyclerView.HORIZONTAL
    }

    override fun scrollHorizontallyBy(dx: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        return scrollBy(dx, recycler, state)
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        return scrollBy(dy, recycler, state)
    }

    protected open fun scrollBy(delta: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        // If there are no view or no movement, return
        if (delta == 0) {
            return 0
        }

        val (childEnd, _) = getGreatestChildEnd()

        val canScrollBackwards = (firstVisiblePosition) >= 0 &&
                0 < scroll &&
                delta < 0

        val canScrollForward = lastVisiblePosition <= state.itemCount &&
                (size) < (childEnd) &&
                delta > 0

        // If can't scroll forward or backwards, return
        if (!(canScrollBackwards || canScrollForward)) {
            return 0
        }

        val correctedDistance = scrollBy(-delta, state, childEnd)

        val direction = if (delta > 0) Direction.END else Direction.START

        recycleChildrenOutOfBounds(direction, recycler)

        fillGap(direction, recycler, state)

        return -correctedDistance
    }

    /**
     * Scrolls distance based on orientation. Corrects distance if out of bounds.
     */
    protected open fun scrollBy(distance: Int, state: RecyclerView.State, childEnd: Int): Int {
        val start = 0

        var correctedDistance = distance

        scroll -= distance

        // Correct scroll if was out of bounds at start
        if (scroll < start) {
            correctedDistance += scroll
            scroll = start
        }

        // Correct scroll if it would make the layout scroll out of bounds at the end
        if (distance < 0 && size - distance > childEnd) {
            correctedDistance = (size - childEnd)
            scroll += distance - correctedDistance
        }

        orientationHelper.offsetChildren(correctedDistance)

        return correctedDistance
    }

    override fun scrollToPosition(position: Int) {
        pendingScrollToPosition = position

        requestLayout()
    }

    override fun smoothScrollToPosition(recyclerView: RecyclerView, state: RecyclerView.State, position: Int) {
        val smoothScroller = object: LinearSmoothScroller(recyclerView.context) {

            override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
                if (childCount == 0) {
                    return null
                }

                val direction = if (targetPosition < firstVisiblePosition) -1 else 1
                return PointF(if (orientation == HORIZONTAL) direction.toFloat() else 0f,
                    if (orientation == VERTICAL) direction.toFloat() else 0f)
            }

            override fun getVerticalSnapPreference(): Int {
                return SNAP_TO_START
            }
        }

        smoothScroller.targetPosition = position
        startSmoothScroll(smoothScroller)
    }

    /**
     * Fills gaps on the layout, on directions [Direction.START] or [Direction.END]
     */
    protected open fun fillGap(direction: Direction, recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (direction == Direction.END) {
            fillAfter(recycler)
        } else {
            fillBefore(recycler)
        }
    }

    /**
     * Fill gaps before the current visible scroll position
     * @param recycler Recycler
     */
    protected open fun fillBefore(recycler: RecyclerView.Recycler) {
        val currentRow = (scroll - getPaddingStartForOrientation()) / getItemSizeForOrientation()
        val lastRow = (scroll + size - getPaddingStartForOrientation()) / getItemSizeForOrientation()

        for (row in (currentRow until lastRow).reversed()) {
            val positionsForRow = rectsHelper.findPositionsForRow(row).reversed()

            for (position in positionsForRow) {
                if (findViewByPosition(position) != null) continue
                makeAndAddView(position, Direction.START, recycler)
            }
        }
    }

    /**
     * Fill gaps after the current layouted views
     * @param recycler Recycler
     */
    protected open fun fillAfter(recycler: RecyclerView.Recycler) {
        val visibleEnd = scroll + size

        val lastAddedRow = layoutEnd / getItemSizeForOrientation()
        val lastVisibleRow =  visibleEnd / getItemSizeForOrientation()

        for (rowIndex in lastAddedRow .. lastVisibleRow) {
            val row = rectsHelper.rows[rowIndex] ?: continue

            for (itemIndex in row) {

                if (findViewByPosition(itemIndex) != null) continue

                makeAndAddView(itemIndex, Direction.END, recycler)
            }
        }
    }

    //==============================================================================================
    //  ~ Decorated position and sizes
    //==============================================================================================

    override fun getDecoratedMeasuredWidth(child: View): Int {
        val position = getPosition(child)
        return childFrames[position]!!.width()
    }

    override fun getDecoratedMeasuredHeight(child: View): Int {
        val position = getPosition(child)
        return childFrames[position]!!.height()
    }

    override fun getDecoratedTop(child: View): Int {
        val position = getPosition(child)
        val decoration = getTopDecorationHeight(child)
        var top = childFrames[position]!!.top + decoration

        if (orientation == RecyclerView.VERTICAL) {
            top -= scroll
        }

        return top
    }

    override fun getDecoratedRight(child: View): Int {
        val position = getPosition(child)
        val decoration = getLeftDecorationWidth(child) + getRightDecorationWidth(child)
        var right = childFrames[position]!!.right + decoration

        if (orientation == RecyclerView.HORIZONTAL) {
            right -= scroll - getPaddingStartForOrientation()
        }

        return right
    }

    override fun getDecoratedLeft(child: View): Int {
        val position = getPosition(child)
        val decoration = getLeftDecorationWidth(child)
        var left = childFrames[position]!!.left + decoration

        if (orientation == RecyclerView.HORIZONTAL) {
            left -= scroll
        }

        return left
    }

    override fun getDecoratedBottom(child: View): Int {
        val position = getPosition(child)
        val decoration = getTopDecorationHeight(child) + getBottomDecorationHeight(child)
        var bottom = childFrames[position]!!.bottom + decoration

        if (orientation == RecyclerView.VERTICAL) {
            bottom -= scroll - getPaddingStartForOrientation()
        }
        return bottom
    }

    //==============================================================================================
    //  ~ Orientation Utils
    //==============================================================================================

    protected open fun getPaddingStartForOrientation(): Int {
        return orientationHelper.startAfterPadding
    }

    protected open fun getPaddingEndForOrientation(): Int {
        return orientationHelper.endPadding
    }

    protected open fun getChildStart(child: View): Int {
        return orientationHelper.getDecoratedStart(child)
    }

    protected open fun getChildEnd(child: View): Int {
        return orientationHelper.getDecoratedEnd(child)
    }

    protected open fun getGreatestChildEnd(): Pair<Int, Int> {
        var greatestEnd = 0
        var greatestI = 0

        for (i in childCount - 1 downTo childCount - getSpanCountForOrientation()) {
            val child = getChildAt(i) ?: continue
            val end = Rect().apply { getDecoratedBoundsWithMargins(child, this) }.run {
                if (orientation == VERTICAL) {
                    bottom
                } else {
                    right
                }
            }

            if (end > greatestEnd) {
                greatestEnd = end
                greatestI = i
            }
        }

        return greatestEnd to greatestI
    }

    protected fun getLeastChildStart(): Pair<Int, Int> {
        var leastStart = Int.MAX_VALUE
        var leastI = 0

        for (i in 0 until getSpanCountForOrientation()) {
            val child = getChildAt(i) ?: continue
            val start = Rect().apply { getDecoratedBoundsWithMargins(child, this) }.run {
                if (orientation == VERTICAL) {
                    top
                } else {
                    left
                }
            }

            if (start < leastStart) {
                leastStart = start
                leastI = i
            }
        }

        return leastStart to leastI
    }

    protected open fun getSpanSizeForOrientationOfChild(index: Int): Int {
        return spanSizeLookup?.getSpanSize(index)?.run {
            if (orientation == HORIZONTAL) width
            else height
        } ?: 1
    }

    protected open fun getItemSizeForOrientation(): Int {
        return if (orientation == RecyclerView.VERTICAL) {
            itemHeight
        } else {
            itemWidth
        }
    }

    open fun getSpanCountForOrientation(): Int {
        return if (orientation == RecyclerView.VERTICAL) {
            horizontalSpanCount
        } else {
            verticalSpanCount
        }
    }

    //==============================================================================================
    //  ~ Save & Restore State
    //==============================================================================================

    override fun onSaveInstanceState(): Parcelable? {
        return if (itemOrderIsStable && childCount > 0) {
            debugLog("Saving first visible position: $firstVisiblePosition")
            SavedState(firstVisiblePosition)
        } else {
            null
        }
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        debugLog("Restoring state")
        val savedState = state as? SavedState
        if (savedState != null) {
            val firstVisibleItem = savedState.firstVisibleItem
            scrollToPosition(firstVisibleItem)
        }
    }

    companion object {
        const val TAG = "SpannedGridLayoutMan"
        const val DEBUG = false

        fun debugLog(message: String) {
            if (DEBUG) Log.d(TAG, message)
        }
    }

    class SavedState(val firstVisibleItem: Int): Parcelable {

        companion object {

            @JvmField val CREATOR = object: Parcelable.Creator<SavedState> {

                override fun createFromParcel(source: Parcel): SavedState {
                    return SavedState(source.readInt())
                }

                override fun newArray(size: Int): Array<SavedState?> {
                    return arrayOfNulls(size)
                }
            }
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeInt(firstVisibleItem)
        }

        override fun describeContents(): Int {
            return 0
        }

    }

}

/**
 * A helper to find free rects in the current layout.
 */
open class RectsHelper(val layoutManager: SpannedGridLayoutManager,
                  @RecyclerView.Orientation val orientation: Int) {

    /**
     * Comparator to sort free rects by position, based on orientation
     */
    private val rectComparator = Comparator<Rect> { rect1, rect2 ->
        when (orientation) {
            RecyclerView.VERTICAL -> {
                if (rect1.top == rect2.top) {
                    if (rect1.left < rect2.left) { -1 } else { 1 }
                } else {
                    if (rect1.top < rect2.top) { -1 } else { 1 }
                }
            }
            RecyclerView.HORIZONTAL -> {
                if (rect1.left == rect2.left) {
                    if (rect1.top < rect2.top) { -1 } else { 1 }
                } else {
                    if (rect1.left < rect2.left) { -1 } else { 1 }
                }
            }
            else -> 0
        }

    }

    val rows = mutableMapOf<Int, Set<Int>>()

    /**
     * Cache of rects that are already used
     */
    private val rectsCache = mutableMapOf<Int, Rect>()

    /**
     * List of rects that are still free
     */
    private val freeRects = mutableListOf<Rect>()

    /**
     * Start row/column for free rects
     */
    val start: Int get() {
        return if (orientation == RecyclerView.VERTICAL) {
            freeRects[0].top * layoutManager.itemHeight
        } else {
            freeRects[0].left * layoutManager.itemWidth
        }
    }

    /**
     * End row/column for free rects
     */
    val end: Int get() {
        return if (orientation == RecyclerView.VERTICAL) {
            (freeRects.last().top + 1) * layoutManager.itemHeight
        } else {
            (freeRects.last().left + 1) * layoutManager.itemWidth
        }
    }

    init {
        // There will always be a free rect that goes to Int.MAX_VALUE
        val initialFreeRect = if (orientation == RecyclerView.VERTICAL) {
            Rect(0, 0, layoutManager.horizontalSpanCount, Int.MAX_VALUE)
        } else {
            Rect(0, 0, Int.MAX_VALUE, layoutManager.verticalSpanCount)
        }
        freeRects.add(initialFreeRect)
    }

    /**
     * Get a free rect for the given span and item position
     */
    fun findRect(position: Int, spanSize: SpanSize): Rect {
        return rectsCache[position] ?: findRectForSpanSize(spanSize)
    }

    /**
     * Find a valid free rect for the given span size
     */
    protected open fun findRectForSpanSize(spanSize: SpanSize): Rect {
        val lane = freeRects.first {
            val itemRect = Rect(it.left, it.top, it.left + spanSize.width, it.top + spanSize.height)
            it.contains(itemRect)
        }

        return Rect(lane.left, lane.top, lane.left + spanSize.width, lane.top + spanSize.height)
    }

    /**
     * Push this rect for the given position, subtract it from [freeRects]
     */
    fun pushRect(position: Int, rect: Rect) {
        val start = if (orientation == RecyclerView.VERTICAL)
            rect.top else
            rect.left
        val startRow = rows[start]?.toMutableSet() ?: mutableSetOf()
        startRow.add(position)
        rows[start] = startRow

        val end = if (orientation == RecyclerView.VERTICAL)
            rect.bottom else
            rect.right
        val endRow = rows[end - 1]?.toMutableSet() ?: mutableSetOf()
        endRow.add(position)
        rows[end - 1] = endRow

        rectsCache[position] = rect
        subtract(rect)
    }

    fun findPositionsForRow(rowPosition: Int): Set<Int> {
        return rows[rowPosition] ?: emptySet()
    }

    /**
     * Remove this rect from the [freeRects], merge and reorder new free rects
     */
    protected open fun subtract(subtractedRect: Rect) {
        val interestingRects = freeRects.filter { it.isAdjacentTo(subtractedRect) || it.intersects(subtractedRect) }

        val possibleNewRects = mutableListOf<Rect>()
        val adjacentRects = mutableListOf<Rect>()

        for (free in interestingRects) {
            if (free.isAdjacentTo(subtractedRect) && !subtractedRect.contains(free)) {
                adjacentRects.add(free)
            } else {
                freeRects.remove(free)

                if (free.left < subtractedRect.left) { // Left
                    possibleNewRects.add(Rect(free.left, free.top, subtractedRect.left, free.bottom))
                }

                if (free.right > subtractedRect.right) { // Right
                    possibleNewRects.add(Rect(subtractedRect.right, free.top, free.right, free.bottom))
                }

                if (free.top < subtractedRect.top) { // Top
                    possibleNewRects.add(Rect(free.left, free.top, free.right, subtractedRect.top))
                }

                if (free.bottom > subtractedRect.bottom) { // Bottom
                    possibleNewRects.add(Rect(free.left, subtractedRect.bottom, free.right, free.bottom))
                }
            }
        }

        for (rect in possibleNewRects) {
            val isAdjacent = adjacentRects.firstOrNull { it != rect && it.contains(rect) } != null
            if (isAdjacent) continue

            val isContained = possibleNewRects.firstOrNull { it != rect && it.contains(rect) } != null
            if (isContained) continue

            freeRects.add(rect)
        }

        freeRects.sortWith(rectComparator)
    }
}

/**
 * Helper to store width and height spans
 */
class SpanSize(val width: Int, val height: Int)