package com.arasthel.spannedgridlayoutmanager.sample

import android.graphics.Rect
import androidx.recyclerview.widget.RecyclerView
import android.view.View

/**
 * Created by Jorge Martín on 2/6/17.
 */

class SpaceItemDecorator(val left: Int,
                         val top: Int,
                         val right: Int,
                         val bottom: Int): androidx.recyclerview.widget.RecyclerView.ItemDecoration() {


    constructor(rect: android.graphics.Rect): this(rect.left, rect.top, rect.right, rect.bottom)

    override fun getItemOffsets(outRect: Rect, view: View, parent: androidx.recyclerview.widget.RecyclerView, state: androidx.recyclerview.widget.RecyclerView.State) {
        outRect.left = this.left
        outRect.top = this.top
        outRect.right = this.right
        outRect.bottom = this.bottom
    }

}