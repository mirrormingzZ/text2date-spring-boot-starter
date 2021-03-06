package cn.mirrorming.sample.test;

import cn.mirrorming.text2date.config.DatetimeRecognizer;
import cn.mirrorming.text2date.time.TimeEntity;
import lombok.Cleanup;
import lombok.SneakyThrows;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.StringUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(SpringRunner.class)
@SpringBootTest
public class TestCase {
    @Autowired
    private DatetimeRecognizer datetimeRecognizer;

    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss");

    @Test
    public void singleTest() {
//        List<Date> dates = datetimeRecognizer.dateParse("明天下午四点到五点去看电影");
        List<TimeEntity> timeEntities = datetimeRecognizer.parse("20分钟后去北京开会");
//        List<TimeEntity> timeEntities = datetimeRecognizer.parse("明天下午两点40到3:40去北京开会");
//        System.out.println(dates);
        System.out.println(timeEntities);
    }

    @Test
    @SneakyThrows
    public void testCase() {
        @Cleanup InputStream inputStream = this.getClass().getResourceAsStream("/testCase.txt");

        @Cleanup InputStreamReader isr = new InputStreamReader(inputStream, StandardCharsets.UTF_8);

        @Cleanup BufferedReader br = new BufferedReader(isr);
        System.out.println("------------------------- Start -------------------------");
        br.lines().filter(str -> !StringUtils.isEmpty(str)).forEach(t -> {
            System.out.println("------------------------------------------------------");
            System.err.println("文本为----->" + t);
            List<Date> dates = datetimeRecognizer.dateParse(t);
            dates.forEach(date -> System.out.println(SDF.format(date)));
            System.out.println("------------------------------------------------------");
        });
    }


}
