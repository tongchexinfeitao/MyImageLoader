package com.ali.myimageloadersample;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ImageView;

public class MainActivity extends AppCompatActivity {

    //这个图的宽高是 400x300，控件的宽高都是200x200，缩放比是2
    private String path = "https://p.ssl.qhimg.com/dmfd/400_300_/t0120b2f23b554b8402.jpg";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        final ImageView iv = findViewById(R.id.iv1);
        //点击加载图片
        iv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //加载图片
                ImageLoader.getInstance().display(path, iv);
            }
        });
    }

}
