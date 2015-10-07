/*
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.du.android.recyclerview;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.support.v7.widget.RecyclerView;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Implementation of RecyclerView.OnItemTouchListener that allows reordering items in RecyclerView by dragging and dropping.
 * Instance of this class should be added to RecylcerView using {@link android.support.v7.widget.RecyclerView#addOnItemTouchListener(android.support.v7.widget.RecyclerView.OnItemTouchListener)} method.
 * <p/>
 * <p/>
 * Use something like this:
 * <pre>
 *  {@code
 * dragDropTouchListener = new DragDropTouchListener(recyclerView, this) {
 *       @Override
 *       protected void onItemSwitch(RecyclerView recyclerView, int from, int to) {
 *           adapter.swapPositions(from, to);
 *           adapter.notifyItemChanged(to);
 *           adapter.notifyItemChanged(from);
 *
 *        @Override
 *        protected void onItemDrop(RecyclerView recyclerView, int position) {
 *       }
 *  };
 *  }
 *   recyclerView.addOnItemTouchListener(dragDropTouchListener);
 * }
 * </pre>
 * <p/>
 * Actual drag is started by calling {@link #startDrag()} somewhere later, for eg. in long touch listener
 */
public class RecycleDragDropManager implements RecyclerView.OnItemTouchListener {

    public static final int ORIENTATION_HORIZONTAL = 0x00000001;

    public static final int ORIENTATION_VERTICAL = 0x00000002;

    private static final String LOG_TAG = "DRAG-DROP";
    private static final int MOVE_DURATION = 150;

    private RecyclerView recyclerView;
    private RecyclerArrayAdapter adapter;
    private Drawable dragHighlight;

    private final int scrollAmount;
    private int downY = -1;
    private int downX = -1;
    private View mobileView;
    private float mobileViewStartY = -1;
    private float mobileViewStartX = -1;
    private int mobileViewCurrentPos = -1;
    private int activePointerId;
    private boolean dragging;
    private boolean enabled = true;

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean allow) {

    }

    /**
     * Bitmap used to build the view displayed as dragging thumbnail.
     */
    private Bitmap draggingThumbnail;

    /**
     * Simple gesture listener used to cached long touched in order to start the drag.
     */
    private GestureDetector.SimpleOnGestureListener simpleOnGestureListener;

    /**
     * Gesture detector used to process event.
     */
    private GestureDetector gestureDetector;

    /**
     * Auto scrolling while dragging.
     */
    private AutoScroller autoScroller;

    /**
     * Used to know if the auto scroller is already started.
     */
    private boolean isScrolling;

    /**
     * Current orientation.
     */
    private int orientation;


    public RecycleDragDropManager(RecyclerView recyclerView, RecyclerArrayAdapter adapter, int orientation) {
        switch (orientation) {
            case ORIENTATION_HORIZONTAL:
            case ORIENTATION_VERTICAL:
                this.orientation = orientation;
                break;
            default:
                throw new IllegalArgumentException("Orientation unknown");
        }

        this.recyclerView = recyclerView;
        this.dragHighlight = recyclerView.getResources().getDrawable(R.drawable.drag_frame);
        this.adapter = adapter;

        dragging = false;

        DisplayMetrics displayMetrics = recyclerView.getResources().getDisplayMetrics();
        this.scrollAmount = (int) (50 / displayMetrics.density);

        // init gesture listener used to catch long pressed event.
        initInternalGestureListener();

        // init auto scroller used to scroll while dragging.
        autoScroller = new AutoScroller();
    }

    @Override
    public boolean onInterceptTouchEvent(RecyclerView recyclerView, MotionEvent event) {
        if (!enabled) return false;

        // dragging not start, listen for long pressed
        if (!dragging) {
            gestureDetector.onTouchEvent(event);
        }

        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                return down(event);

            case MotionEvent.ACTION_MOVE:
                return dragging && move(event);

            case MotionEvent.ACTION_UP:
                return up(event);

            case MotionEvent.ACTION_CANCEL:
                return cancel(event);

        }
        return false;
    }

    @Override
    public void onTouchEvent(RecyclerView view, MotionEvent event) {
        if (!dragging) return;

        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_MOVE:
                move(event);
                break;

            case MotionEvent.ACTION_UP:
                up(event);
                break;

            case MotionEvent.ACTION_CANCEL:
                cancel(event);
                break;

        }
    }

    /**
     * Call this to indicate drag start
     */
    public void startDrag() {
        View viewUnder = recyclerView.findChildViewUnder(downX, downY);
        if (viewUnder == null) return;
        dragging = true;

        mobileViewCurrentPos = recyclerView.getChildPosition(viewUnder);

        mobileView = getDraggingView(viewUnder);
        mobileView.setX(viewUnder.getX());
        mobileView.setY(viewUnder.getY());
        mobileViewStartY = mobileView.getY();
        mobileViewStartX = mobileView.getX();

        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ((ViewGroup) recyclerView.getParent()).addView(mobileView, lp);
        mobileView.bringToFront();
        viewUnder.setVisibility(View.INVISIBLE);

    }

    private boolean down(MotionEvent event) {
        activePointerId = event.getPointerId(0);
        downY = (int) event.getY();
        downX = (int) event.getX();
        return false;
    }


    private boolean move(MotionEvent event) {
        if (activePointerId == -1) {
            return false;
        }

        int pointerIndex = event.findPointerIndex(activePointerId);

        if (orientation == ORIENTATION_HORIZONTAL) {
            int currentX = (int) event.getX(pointerIndex);
            float deltaX = currentX - downX;
            float mobileViewX = mobileViewStartX + deltaX;
            mobileView.setTranslationX(mobileViewX);
        } else if (orientation == ORIENTATION_VERTICAL) {
            int currentY = (int) event.getY(pointerIndex);
            float deltaY = currentY - downY;
            float mobileViewY = mobileViewStartY + deltaY;
            mobileView.setTranslationY(mobileViewY);
        }


        switchViewsIfNeeded();
        scrollIfNeeded();
        return true;
    }

    private void switchViewsIfNeeded() {
        int pos = mobileViewCurrentPos;
        int abovePos = pos - 1;
        int belowPos = pos + 1;

        View previousView = getViewByPosition(abovePos);
        View nextView = getViewByPosition(belowPos);


        if (orientation == ORIENTATION_VERTICAL) {
            int mobileViewY = (int) mobileView.getY();
            if (previousView != null && previousView.getY() > -1 && mobileViewY < previousView.getY()) {
                Log.d(LOG_TAG, String.format("Got previousView with y = %s, for position = %s, %s", previousView.getY(), abovePos, previousView));
                doSwitch(previousView, pos, abovePos);
            }
            if (nextView != null && nextView.getY() > -1 && mobileViewY > nextView.getY()) {
                Log.d(LOG_TAG, String.format("Got nextView with y = %s, for position = %s, %s", nextView.getY(), belowPos, nextView));
                doSwitch(nextView, pos, belowPos);
            }
        } else if (orientation == ORIENTATION_HORIZONTAL) {
            int mobileViewX = (int) mobileView.getX();
            if (previousView != null && previousView.getX() > -1 && mobileViewX < previousView.getX()) {
                Log.d(LOG_TAG, String.format("Got previousView with y = %s, for position = %s, %s", previousView.getX(), abovePos, previousView));
                doSwitch(previousView, pos, abovePos);
            }
            if (nextView != null && nextView.getX() > -1 && mobileViewX > nextView.getX()) {
                Log.d(LOG_TAG, String.format("Got nextView with y = %s, for position = %s, %s", nextView.getX(), belowPos, nextView));
                doSwitch(nextView, pos, belowPos);
            }
        }


    }

    private void doSwitch(final View switchView, final int originalViewPos, final int switchViewPos) {
        View originalView = getViewByPosition(originalViewPos);


        onItemSwitch(recyclerView, originalViewPos, switchViewPos);

        switchView.setVisibility(View.INVISIBLE);
        originalView.setVisibility(View.VISIBLE);

        if (orientation == ORIENTATION_VERTICAL) {
            int switchViewTop = switchView.getTop();
            int originalViewTop = originalView.getTop();
            int delta = originalViewTop - switchViewTop;
            originalView.setTranslationY(-delta);
            originalView.animate().translationYBy(delta).setDuration(MOVE_DURATION);
        } else if (orientation == ORIENTATION_HORIZONTAL) {
            int switchViewLeft = switchView.getLeft();
            int originalViewLeft = originalView.getLeft();
            int delta = originalViewLeft - switchViewLeft;
            originalView.setTranslationX(-delta);
            originalView.animate().translationXBy(delta).setDuration(MOVE_DURATION);
        }


        mobileViewCurrentPos = switchViewPos;

    }

    private boolean up(MotionEvent event) {
        endDrag();
        return false;
    }

    private boolean cancel(MotionEvent event) {
        endDrag();
        return false;
    }

    /**
     * Animate dragged view to it's position.
     */
    private void endDrag() {
        final View view = getViewByPosition(mobileViewCurrentPos);
        if (view != null && mobileView != null) {
            ViewPropertyAnimator animator = mobileView.animate();

            if (orientation == ORIENTATION_VERTICAL) {
                float y = view.getY();
                animator.translationY(y).setDuration(MOVE_DURATION);
            } else if (orientation == ORIENTATION_HORIZONTAL) {
                float x = view.getX();
                animator.translationX(x).setDuration(MOVE_DURATION);
            }

            animator.setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    view.setVisibility(View.VISIBLE);
                    if (mobileView != null) {
                        ViewGroup parent = (ViewGroup) mobileView.getParent();
                        parent.removeView(mobileView);
                        draggingThumbnail.recycle();
                        draggingThumbnail = null;
                        mobileView = null;
                        recyclerView.removeCallbacks(autoScroller);
                    }
                }
            });
            animator.start();

        }

        dragging = false;
        mobileViewStartY = -1;
        mobileViewStartX = -1;
        mobileViewCurrentPos = -1;

    }

    /**
     * Implementation usually do 2 things: change positions of items in RecyclerView.Adapter and notify it about changes
     *
     * @param recyclerView view the item is being dragged in
     * @param from         original (start) drag position within adapter
     * @param to           new drag position withing adapter
     */
    private void onItemSwitch(RecyclerView recyclerView, int from, int to) {
        adapter.swapPositions(from, to);
        adapter.notifyItemChanged(to);
    }

    private View getViewByPosition(int position) {
        RecyclerView.ViewHolder viewHolder = recyclerView.findViewHolderForPosition(position);
        return viewHolder == null ? null : viewHolder.itemView;
    }


    private boolean scrollIfNeeded() {

        int hoverViewPosition = 0;
        int hoverViewSize = 0;
        int previousBoundary = 0;
        int nextBoundary = 0;

        if (orientation == ORIENTATION_VERTICAL) {
            previousBoundary = 0;
            nextBoundary = recyclerView.getHeight();
            hoverViewSize = mobileView.getHeight();
            hoverViewPosition = (int) mobileView.getY();
        } else if (orientation == ORIENTATION_HORIZONTAL) {
            previousBoundary = 0;
            nextBoundary = recyclerView.getWidth();
            hoverViewSize = mobileView.getWidth();
            hoverViewPosition = (int) mobileView.getX();
        }

        if (hoverViewPosition <= previousBoundary && !isScrolling) {
            isScrolling = true;
            autoScroller.startScrolling(AutoScroller.START);
            return true;
        } else if (hoverViewPosition + hoverViewSize >= nextBoundary && !isScrolling) {
            isScrolling = true;
            autoScroller.startScrolling(AutoScroller.END);
            return true;
        } else if (hoverViewPosition >= previousBoundary && hoverViewPosition + hoverViewSize <= nextBoundary && isScrolling) {
            autoScroller.stopScrolling();
            isScrolling = false;
        }
        return false;
    }

    /**
     * Build view which will be used while user performing a drag event.
     *
     * @param v touched view after a long press.
     * @return View which will be used as dragging thumbnail.
     */
    private View getDraggingView(View v) {
        //Clear ripple effect to not get into screenshot,
        // need something more clever here
        if (v instanceof FrameLayout) {
            FrameLayout frameLayout = (FrameLayout) v;
            Drawable foreground = frameLayout.getForeground();
            if (foreground != null) foreground.setVisible(false, false);
        } else {
            if (v.getBackground() != null) v.getBackground().setVisible(false, false);
        }

        draggingThumbnail = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(draggingThumbnail);
        v.draw(canvas);

        //Drag highlight, usually border
        if (dragHighlight != null) {
            dragHighlight.setBounds(0, 0, draggingThumbnail.getWidth(), draggingThumbnail.getHeight());
            dragHighlight.draw(canvas);
        }

        ImageView imageView = new ImageView(recyclerView.getContext());
        imageView.setImageBitmap(draggingThumbnail);
        return imageView;
    }

    /**
     * Initialize internal gesture listener used to catch long press event on a raw in order to
     * start the drag event.
     */
    private void initInternalGestureListener() {
        simpleOnGestureListener = new GestureDetector.SimpleOnGestureListener() {

            @Override
            public void onLongPress(MotionEvent e) {
                startDrag();
            }

        };
        gestureDetector = new GestureDetector(recyclerView.getContext(), simpleOnGestureListener);
    }

    /**
     * Enable/disable drag/drop
     *
     * @param enabled
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }


    /**
     * Auto scroller used to scroll the recycler view while dragging.
     */
    private final class AutoScroller implements Runnable {

        /**
         * Scroll to the start of the recycle view.
         */
        static final int START = -1;

        /**
         * Scroll to the end of the recycle view.
         */
        static final int END = 1;

        private int direction;

        public AutoScroller() {

        }

        public void startScrolling(int direction) {
            if (direction != START && direction != END) {
                throw new IllegalArgumentException("Direction unknown");
            }
            this.direction = direction;
            recyclerView.post(this);

        }

        public void stopScrolling() {
            recyclerView.removeCallbacks(this);
        }

        @Override
        public void run() {
            if (orientation == ORIENTATION_VERTICAL) {
                recyclerView.scrollBy(0, direction * scrollAmount);
                recyclerView.post(this);
            } else if (orientation == ORIENTATION_HORIZONTAL) {
                recyclerView.scrollBy(direction * scrollAmount, 0);
                recyclerView.post(this);
            }

        }
    }

}
