package com.example.vuzixcameraapp;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

//modified ScrollView that allows the user to still swipe and press the screen through the overlay

public class PassThroughScrollView extends ScrollView {
    public PassThroughScrollView(Context context){
        super(context);
    }
    public PassThroughScrollView(Context context, AttributeSet attrs){
        super(context, attrs);
    }
    public PassThroughScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            return false;
        }
        return super.onInterceptTouchEvent(ev);
    }
}
