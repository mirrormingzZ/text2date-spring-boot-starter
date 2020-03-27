package cn.mirrorming.text2date.time;

import cn.mirrorming.text2date.number.ChineseNumbers;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * 时间实体识别器 主要工作类
 */
@Slf4j
public class TimeEntityRecognizer {
    private static final TimeZone CHINA_TIME_ZONE = TimeZone.getTimeZone("Asia/Shanghai");
    private Pattern pattern;
    private List<String> regexList;

    public TimeEntityRecognizer() throws IOException {
        this(TimeEntityRecognizer.class.getResourceAsStream("/time.regex"));
    }

    public TimeEntityRecognizer(String file) throws IOException {
        this(new FileInputStream(file));
    }

    public TimeEntityRecognizer(InputStream in) throws IOException {
        regexList = IOUtils.readLines(in, "UTF-8")
                .stream()
                .map(StringUtils::stripToNull)
                .filter(item -> StringUtils.isNotEmpty(item) && !item.startsWith("#")).distinct()
//                .sorted((o1, o2) -> o2.length() - o1.length())
                .collect(Collectors.toList());

        if (log.isTraceEnabled()) {
            log.trace("input regex[size={}, text={}]", regexList.size(), regexList);
        }
        long start = System.currentTimeMillis();
        //读取pattern
        this.pattern = Pattern.compile(regexList.stream().map(item -> "(" + item + ")").collect(Collectors.joining("|")));
        long end = System.currentTimeMillis();
        log.info("pattern initialized for {} patterns, time used(ms):{}", regexList.size(), (end - start));
    }

    public List<TimeEntity> parse(String text) {
        return parse(text, CHINA_TIME_ZONE);
    }

    public List<TimeEntity> parse(String text, TimeZone timeZone) {
        return parse(text, timeZone, Calendar.getInstance(timeZone).getTime());
    }

    public List<TimeEntity> parse(String text, TimeZone timeZone, Date relative) {
        List<TimeEntity> result = new ArrayList<>();
        int offset;
        Matcher match = pattern.matcher(text);
        /**
         * 匹配时间信息，连续的时间信息merge到一个实体中
         */
        while (match.find()) {
            TimeEntity lastEntity = result.isEmpty() ? null : result.get(result.size() - 1);
            offset = match.start();
            String matchedText = match.group();
            if (lastEntity != null && offset == lastEntity.getOffset() + lastEntity.getOriginal().length()) {
                lastEntity.setOriginal(lastEntity.getOriginal() + matchedText);
            } else {
                TimeEntity timeEntity = new TimeEntity(matchedText, offset);
                result.add(timeEntity);
            }
        }
        Iterator<TimeEntity> iterator = result.iterator();
        Date lastRelative = relative;
        while (iterator.hasNext()) {
            TimeEntity timeEntity = iterator.next();
            Date date = parseTime(
                    timeEntity.getOriginal(),
                    timeZone,
                    lastRelative,
                    lastRelative.equals(relative),
                    timeEntity);
            if (null != date) {
                lastRelative = date;
                //识别时间循环，放到这里因为要考虑实体字符串的上下文，而时间实体中只是有识别出的时间字符串，缺乏上下文信息
                offset = timeEntity.getOffset();
                if (offset > 1 && text.charAt(offset - 1) == '到') {
                    timeEntity.setStart(false);
                    timeEntity.setEnd(true);
                } else if (offset + timeEntity.getOriginal().length() < text.length()
                        && text.charAt(offset + timeEntity.getOriginal().length()) == '到') {
                    timeEntity.setStart(true);
                    timeEntity.setEnd(false);
                }
            } else {
                iterator.remove();
            }
        }
        //double check time start and end, 比如5点到我这里来，5点会被设置为start=true，需要纠正过来
        iterator = result.iterator();
        TimeEntity prev = null;
        while (iterator.hasNext()) {
            TimeEntity timeEntity = iterator.next();
            if (timeEntity.isEnd() && prev == null) {
                timeEntity.setEnd(false);
            } else if (timeEntity.isStart() && !iterator.hasNext()) {
                timeEntity.setStart(false);
            }
            //每月三号上午8点到10点,对于这样的时间cycle，修正10点这个实体不带cycle属性的问题
            if (timeEntity.isEnd() && prev != null) {
                if (prev.getCycle() != null && timeEntity.getCycle() == null) {
                    timeEntity.setCycle(prev.getCycle());
                }
                if (prev.getValue().getTime() > timeEntity.getValue().getTime()) {
                    timeEntity.setValue(new Date(timeEntity.getValue().getTime() + 12 * 60 * 60 * 1000));
                }
            }
            prev = timeEntity;
        }
        return result;
    }

    /**
     * 参考StringPreHandlingModule, 将中文表达的日期、时间转化为数字表达
     *
     * @param text
     * @return
     */
    static final Pattern NUMBER_P = Pattern.compile("[一二两三四五六七八九十]+");

    private String normalizeTimeString(String text) {
        text = text.replace("周日", "周7").replace("：", ":");
        text = text.replace("周天", "周7");
        text = text.replace("星期日", "星期7");
        text = text.replace("星期天", "星期7");


        Matcher m = NUMBER_P.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean result = m.find();
        while (result) {
            String group = m.group();
            Number number = ChineseNumbers.chineseNumberToEnglish(group);
            m.appendReplacement(sb, String.valueOf(number.intValue()));
            result = m.find();
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private boolean validTime(int[] arr) {
        int sum = Arrays.stream(arr).sum();
        //month
        if (arr[1] > 12) {
            return false;
        }
        //day
        if (arr[2] > 31) {
            return false;
        }
        //hour
        if (arr[3] > 24) {
            return false;
        }
        //minute
        if (arr[4] > 59) {
            return false;
        }
        //second
        if (arr[5] > 59) {
            return false;
        }
        return sum != -6;
    }

    private Date parseTime(String text, TimeZone timeZone, Date relative, boolean isDefaultRelative, TimeEntity timeEntity) {
        text = normalizeTimeString(text);
        int year = parseYear(text);
        int month = parseMonth(text);
        int day = parseDay(text);
        int hour = parseHour(text);
        int minute = parseMinute(text);
        int second = parseSecond(text);
        int[] arr = {year, month, day, hour, minute, second};
        String cycle = parseCycle(text);
        if (null != cycle) {
            timeEntity.setCycle(Cycle.parseCycle(cycle));
        }
        overallParse(text, arr);
        parseRelative(text, timeZone, relative, arr);
        parseCurrentRelative(text, timeZone, relative, arr);
        if (!validTime(arr)) {
            return null;
        }
        normalize(text, arr, timeZone, relative, isDefaultRelative);
        Calendar calendar = Calendar.getInstance(timeZone);
        calendar.clear();

        final int[] fields = {
                Calendar.YEAR,
                Calendar.MONTH,
                Calendar.DAY_OF_MONTH,
                Calendar.HOUR_OF_DAY,
                Calendar.MINUTE,
                Calendar.SECOND
        };

        for (int i = 0; i < arr.length; i++) {
            if (arr[i] > 0) {
                calendar.set(fields[i], arr[i]);
            }
        }
        if (arr[1] > 0) {
            calendar.set(Calendar.MONTH, arr[1] - 1);
        }
        Date ret = calendar.getTime();
        timeEntity.setValue(calendar.getTime());
        if (arr[3] + arr[4] + arr[5] <= -3) {//没有时间信息
            timeEntity.setDateOnly(true);
        }
        return ret;
    }

    /**
     * 将第一个下标对应数值为正的前面几个字段都设置为相对时间的对应值
     *
     * @param arr
     * @param relative
     */
    private static final Pattern TIME_MODIFIER_PATTERN = Pattern.compile("(早|早晨|早上|上午|中午|午后|下午|傍晚|晚上|晚间|夜里|夜|凌晨|深夜|pm|PM)");

    /**
     * 对于arr数组中头部==-1的元素，用相对时间替换，同时对于当天已经是过去的时间表达，偏移到当天12小时之后
     *
     * @param text
     * @param arr
     * @param timeZone
     * @param relative
     * @param isDefaultRelative
     */
    private void normalize(String text, int[] arr, TimeZone timeZone, Date relative, boolean isDefaultRelative) {
        int j = 0;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] >= 0) {
                j = i;
                break;
            }
        }
        Calendar calender = Calendar.getInstance(timeZone);

        //如果没有相对日期约束，时间又是过去的时间，并且当前识别的hour<=12, 设置为当天最近的一个未来时间
        Matcher matcher = TIME_MODIFIER_PATTERN.matcher(text);
        if (!matcher.find() && isDefaultRelative && arr[2] < 0 && arr[3] < calender.get(Calendar.HOUR_OF_DAY) &&
                arr[3] <= 12) {
            arr[3] += 12;
        }

        calender.setTime(relative);

        final int[] fields = {
                Calendar.YEAR,
                Calendar.MONTH,
                Calendar.DAY_OF_MONTH,
                Calendar.HOUR_OF_DAY,
                Calendar.MINUTE,
                Calendar.SECOND
        };

        for (int i = 0; i < j; i++) {
            if (arr[i] < 0) {
                if (i == 1) {
                    arr[i] = calender.get(Calendar.MONTH) + 1;
                } else {
                    arr[i] = calender.get(fields[i]);
                }
            }
        }
    }

    private static final Pattern YEAR_2_DIGIT_PATTERN = Pattern.compile("[0-9]{2}(?=年)");
    private static final Pattern YEAR_4_DIGIT_PATTERN = Pattern.compile("[0-9]?[0-9]{3}(?=年)");

    private int parseYear(String text) {
        int year = -1;
        /*
         * 不仅局限于支持1XXX年和2XXX年的识别，可识别三位数和四位数表示的年份
         */
        Matcher match = YEAR_4_DIGIT_PATTERN.matcher(text);
        if (match.find()) {
            year = Integer.parseInt(match.group());
        } else {
            match = YEAR_2_DIGIT_PATTERN.matcher(text);
            if (match.find()) {
                //注意这里逻辑上是不严格的，更多是从当前时间节点上大家的输入习惯
                year = Integer.parseInt(match.group());
                if (year >= 0 && year < 100) {
                    if (year < 30) {
                        year += 2000;
                    } else {
                        year += 1900;
                    }
                }

            }
        }
        return year;
    }

    private static final Pattern MONTH_PATTERN = Pattern.compile("((?<!\\d))((10)|(11)|(12)|([1-9]))(?=月)");

    private int parseMonth(String text) {
        Matcher match = MONTH_PATTERN.matcher(text);
        if (match.find()) {
            return Integer.parseInt(match.group());
        }
        return -1;
    }

    private static final Pattern DAY_PATTERN = Pattern.compile("(((?<!\\d))([0-3][0-9]|[1-9])(?=(日|号)))|((?<=月)([0-3][0-9]|[1-9])(?=(日|号))?)");
//    private static final Pattern MONTH_DAY_PATTERN = Pattern.compile("(?<=月)([0-3][0-9]|[1-9])(?=(日|号))?");

    private int parseDay(String text) {
        Matcher match = DAY_PATTERN.matcher(text);
        if (match.find()) {
//            int g = match.groupCount();
//            for (int i = 0; i < g; i++) {
//                String group = match.group(i);
//                LOGGER.debug("group {}, str:{}", i, group);
//            }
            return Integer.parseInt(match.group());
        }
        return -1;
    }


    private static final Pattern HOUR_PATTERN = Pattern.compile("(?<!(周|星期))([0-2]?[0-9])(?=(点|时))");//(?<!(周|星期))([0-2]?[0-9])(?=(点|时))
    private static final Pattern EARLY_MORNING_PATTERN = Pattern.compile("凌晨");
    private static final Pattern MORNING_PATTERN = Pattern.compile("(早上|早晨)");
    private static final Pattern FORENOON_PATTERN = Pattern.compile("(上午)|(am)|(AM)");
    private static final Pattern NOON_PATTERN = Pattern.compile("(中午)|(午间)");
    private static final Pattern AFTERNOON_PATTERN = Pattern.compile("(下午)|(午后)|(pm)|(PM)");
    private static final Pattern EVENING_PATTERN = Pattern.compile("(傍晚)");
    private static final Pattern NIGHT_PATTERN = Pattern.compile("(?<!傍)晚");

//    private static final Pattern NOON_PATTERN=Pattern.compile("中午(?![1-3]+点)");
//    private static final Pattern AFTER_NOON_NOON_PATTERN=Pattern.compile("中午(?![1-3]+点)");


    /**
     * 凌晨：0-5
     * 早上：5-11
     * 中午：11-13
     * 下午：13-17
     * 傍晚：17-19
     * 晚上：19-24
     *
     * @param text
     * @return
     */
    private int parseHour(String text) {
        /*
         * 清除只能识别11-99时的bug
         */

        int hour = -1;
        Matcher match = HOUR_PATTERN.matcher(text);
        if (match.find()) {
            hour = Integer.parseInt(match.group());
        }
        match = EARLY_MORNING_PATTERN.matcher(text);
        if (match.find()) {
            if (hour < 0) {
                hour = 1;
            }
        }

        match = MORNING_PATTERN.matcher(text);
        if (match.find()) {
            if (hour < 0) {
                hour = 6;
            }
        }
        match = FORENOON_PATTERN.matcher(text);
        if (match.find()) {
            if (hour < 0) {
                hour = 9;
            }
        }
        /*
         * 对关键字：中午,午间,下午,午后,晚上,傍晚,晚间,晚,pm,PM的正确24小时时间计算
         * 规约：
         * 1.中午/午间0-10点视为12-22点
         * 2.下午/午后0-11点视为12-23点
         * 3.晚上/傍晚/晚间/晚1-11点视为13-23点，12点视为0点
         * 4.0-11点pm/PM视为12-23点
         *
         * add by 曹零
         */
        match = NOON_PATTERN.matcher(text);
        if (match.find()) {
            if (hour >= 0 && hour <= 10) {
                hour += 12;
            } else if (hour < 0) {
                hour = 12;
            }
        }

        match = AFTERNOON_PATTERN.matcher(text);
        if (match.find()) {
            if (hour >= 0 && hour <= 11) {
                hour += 12;
            } else if (hour < 1) {
                hour = 14;
            }
        }

        match = EVENING_PATTERN.matcher(text);
        if (match.find()) {
            if (hour > 0 && hour < 11) {
                hour += 12;
            } else if (hour < 1) {
                hour = 18;
            }
        } else {
            match = NIGHT_PATTERN.matcher(text);
            if (match.find()) {
                if (hour >= 1 && hour <= 11) {
                    hour += 12;
                } else if (hour == 12) {
                    hour += 12;
                } else if (hour < 0) {
                    hour = 20;
                }
            }
        }
        return hour;
    }

    private static final Pattern MINUTE_PATTERN = Pattern.compile("([0-5]?[0-9](?=分(?!钟)))|((?<=((?<!(周|星期|\\d))([0-2]?[0-9])(点|时)))[0-5]?[0-9](?!刻))");
    private static final Pattern ONE_QUARTER_PATTERN = Pattern.compile("(?<=[点时])[1一]刻(?!钟)");
    private static final Pattern TWO_QUARTER_PATTERN = Pattern.compile("(?<=[点时])半");
    private static final Pattern THREE_QUARTER_PATTERN = Pattern.compile("(?<=[点时])[3三]刻(?!钟)");

    /**
     * @param text
     * @return
     */
    private int parseMinute(String text) {
        /*
         * 添加了省略“分”说法的时间
         * 如17点15
         */
        int minute = -1;
        Matcher match = MINUTE_PATTERN.matcher(text);
        if (match.find()) {
            minute = Integer.parseInt(match.group());
        }
        /*
         * 添加对一刻，半，3刻的正确识别（1刻为15分，半为30分，3刻为45分）
         *
         * add by 曹零
         */
        match = ONE_QUARTER_PATTERN.matcher(text);
        if (match.find()) {
            minute = 15;
        }

        match = TWO_QUARTER_PATTERN.matcher(text);
        if (match.find()) {
            minute = 30;
        }

        match = THREE_QUARTER_PATTERN.matcher(text);
        if (match.find()) {
            minute = 45;
        }
        return minute;
    }

    private static final Pattern SECOND_PATTERN = Pattern.compile("([0-5]?[0-9](?=秒))|((?<=分)[0-5]?[0-9])");

    private int parseSecond(String text) {
        /*
         * 添加了省略“分”说法的时间
         * 如17点15分32
         */
        Matcher match = SECOND_PATTERN.matcher(text);
        if (match.find()) {
            return Integer.parseInt(match.group());
        }
        return -1;
    }

    private static final Pattern HOUR_MINUTE_SECOND_PATTERN = Pattern.compile("(?<!(周|星期))([0-2]?[0-9]):[0-5]?[0-9]:[0-5]?[0-9]");

    private static final Pattern HOUR_MINUTE_PATTERN = Pattern.compile("(?<!(周|星期))([0-2]?[0-9]):[0-5]?[0-9]");

    private static final Pattern DASH_YEAR_MONTH_DAY = Pattern.compile("[0-9]?[0-9]?[0-9]{2}-((10)|(11)|(12)|([1-9]))-((?<!\\d))([0-3][0-9]|[1-9])");

    private static final Pattern SLASH_YEAR_MONTH_DAY = Pattern.compile("((10)|(11)|(12)|([1-9]))/((?<!\\d))([0-3][0-9]|[1-9])/[0-9]?[0-9]?[0-9]{2}");

    private static final Pattern DOT_YEAR_MONTH_DAY = Pattern.compile("[0-9]?[0-9]?[0-9]{2}\\.((10)|(11)|(12)|([1-9]))\\.((?<!\\d))([0-3][0-9]|[1-9])");

    private void overallParse(String text, int[] arr) {
        /*
         * 修改了函数中所有的匹配规则使之更为严格
         */
        Matcher match = HOUR_MINUTE_SECOND_PATTERN.matcher(text);
        if (match.find()) {
            String[] splits = match.group().split(":");
            arr[3] = Integer.parseInt(splits[0]);
            arr[4] = Integer.parseInt(splits[1]);
            arr[5] = Integer.parseInt(splits[2]);
        } else {
            /*
             * 添加了省略秒的:固定形式的时间规则匹配
             * add by 曹零
             */
            match = HOUR_MINUTE_PATTERN.matcher(text);
            if (match.find()) {
                String[] splits = match.group().split(":");
                arr[3] = Integer.parseInt(splits[0]);
                arr[4] = Integer.parseInt(splits[1]);
            }
        }
        /*
         * 增加了:固定形式时间表达式的
         * 中午,午间,下午,午后,晚上,傍晚,晚间,晚,pm,PM
         * 的正确时间计算，规约同上
         */
        match = NOON_PATTERN.matcher(text);
        if (match.find()) {
            if (arr[3] >= 0 && arr[3] <= 10) {
                arr[3] += 12;
            }
        }

        match = AFTERNOON_PATTERN.matcher(text);
        if (match.find()) {
            if (arr[3] >= 0 && arr[3] <= 11) {
                arr[3] += 12;
            }
        }

        match = NIGHT_PATTERN.matcher(text);
        if (match.find()) {
            if (arr[3] >= 1 && arr[3] <= 11) {
                arr[3] += 12;
            } else if (arr[3] == 12) {
                arr[3] = 0;
            }
        }


        match = DASH_YEAR_MONTH_DAY.matcher(text);
        if (match.find()) {
            String[] splits = match.group().split("-");
            arr[0] = Integer.parseInt(splits[0]);
            arr[1] = Integer.parseInt(splits[1]);
            arr[2] = Integer.parseInt(splits[2]);
        }

        match = SLASH_YEAR_MONTH_DAY.matcher(text);
        if (match.find()) {
            String[] splits = match.group().split("/");
            arr[1] = Integer.parseInt(splits[0]);
            arr[2] = Integer.parseInt(splits[1]);
            arr[0] = Integer.parseInt(splits[2]);
        }

        /*
         * 增加了:固定形式时间表达式 年.月.日 的正确识别
         * add by 曹零
         */
        match = DOT_YEAR_MONTH_DAY.matcher(text);
        if (match.find()) {
            String[] splits = match.group().split("\\.");
            arr[0] = Integer.parseInt(splits[0]);
            arr[1] = Integer.parseInt(splits[1]);
            arr[2] = Integer.parseInt(splits[2]);
        }
    }

    private static final Pattern HALF_AN_HOUR_BEFORE_PATTERN = Pattern.compile("(\\d?+(个?+半+个?+小时[以之]?前))");
    private static final Pattern HALF_AN_HOUR_AFTER_PATTERN = Pattern.compile("(\\d?+(个?+半+个?+小时[以之]?后))");

    private static final Pattern MINUTE_BEFORE_PATTERN = Pattern.compile("(\\d+(?=分钟[以之]?前))|((?<=提前)\\d+(?=分钟))");
    private static final Pattern MINUTE_AFTER_PATTERN = Pattern.compile("\\d+(?=分钟[以之]?后)");
    private static final Pattern HOURS_BEFORE_PATTERN = Pattern.compile("(\\d+(?=(个)?小时[以之]?前))|((?<=提前)\\d+(?=(个)?小时))");
    private static final Pattern HOURS_AFTER_PATTERN = Pattern.compile("\\d+(?=(个)?小时[以之]?后)");
    private static final Pattern DAYS_BEFORE_PATTERN = Pattern.compile("(\\d+(?=天[以之]?前))|((?<=提前)?\\d+(?=天))");
    private static final Pattern DAYS_AFTER_PATTERN = Pattern.compile("\\d+(?=天[以之]?后)");

    private static final Pattern MONTH_BEFORE_PATTERN = Pattern.compile("\\d+(?=(个)?月[以之]?前)");
    private static final Pattern MONTH_AFTER_PATTERN = Pattern.compile("\\d+(?=(个)?月[以之]?后)");
    private static final Pattern YEAR_BEFORE_PATTERN = Pattern.compile("\\d+(?=年[以之]?前)");
    private static final Pattern YEAR_AFTER_PATTERN = Pattern.compile("\\d+(?=年[以之]?后)");

    private void parseRelative(String text, TimeZone timeZone, Date relative, int[] arr) {

        Calendar calendar = Calendar.getInstance(timeZone);
        calendar.setFirstDayOfWeek(Calendar.MONDAY);
        calendar.setTime(relative);
        //年，月，日，小时，分钟
        boolean[] flag = {false, false, false, false, false};

        Matcher match = HOURS_BEFORE_PATTERN.matcher(text);
        if (match.find()) {
            int hour = Integer.parseInt(match.group());
            calendar.add(Calendar.HOUR_OF_DAY, -hour);
            flag[3] = true;
            flag[4] = true;
        }
        match = HOURS_AFTER_PATTERN.matcher(text);
        if (match.find()) {
            int hour = Integer.parseInt(match.group());
            calendar.add(Calendar.HOUR_OF_DAY, hour);
            flag[3] = true;
            flag[4] = true;
        }

        //匹配xx个半小时前
        match = HALF_AN_HOUR_BEFORE_PATTERN.matcher(text);
        if (match.find()) {

            String group = match.group();
            if (group.startsWith("半")) {
                calendar.add(Calendar.MINUTE, -30);
                flag[4] = true;
            } else {
                calendar.add(Calendar.HOUR_OF_DAY, -Integer.parseInt(group.substring(0, 1)));
                calendar.add(Calendar.MINUTE, -30);
                flag[3] = true;
                flag[4] = true;
            }
        }

        //匹配xx个半小时后
        match = HALF_AN_HOUR_AFTER_PATTERN.matcher(text);
        if (match.find()) {
            String group = match.group();
            if (group.startsWith("半")) {
                calendar.add(Calendar.MINUTE, 30);
                flag[4] = true;
            } else {
                calendar.add(Calendar.HOUR_OF_DAY, Integer.parseInt(group.substring(0, 1)));
                calendar.add(Calendar.MINUTE, 30);
                flag[3] = true;
                flag[4] = true;
            }
        }

        match = MINUTE_BEFORE_PATTERN.matcher(text);
        if (match.find()) {
            int minute = Integer.parseInt(match.group());
            calendar.add(Calendar.MINUTE, -minute);
            flag[4] = true;
        }

        match = MINUTE_AFTER_PATTERN.matcher(text);
        if (match.find()) {
            int minute = Integer.parseInt(match.group());
            calendar.add(Calendar.MINUTE, minute);
            flag[4] = true;
        }

//        match = HALF_AN_HOUR_PATTERN.matcher(text);
//        if (match.find()) {
//            int day = Integer.parseInt(match.group());
//            calendar.add(Calendar.MINUTE, -30);
//            flag[4] = true;
//        }

        match = DAYS_BEFORE_PATTERN.matcher(text);
        if (match.find()) {
            int day = Integer.parseInt(match.group());
            calendar.add(Calendar.DATE, -day);
            flag[2] = true;
            flag[3] = true;
            flag[4] = true;
        }


        match = DAYS_AFTER_PATTERN.matcher(text);
        if (match.find()) {
            int day = Integer.parseInt(match.group());
            calendar.add(Calendar.DATE, day);
            flag[2] = true;
            flag[3] = true;
            flag[4] = true;
        }

        match = MONTH_BEFORE_PATTERN.matcher(text);
        if (match.find()) {
            int month = Integer.parseInt(match.group());
            calendar.add(Calendar.MONTH, -month);
            flag[1] = true;
        }

        match = MONTH_AFTER_PATTERN.matcher(text);
        if (match.find()) {
            int month = Integer.parseInt(match.group());
            calendar.add(Calendar.MONTH, month);
            flag[1] = true;
        }

        match = YEAR_BEFORE_PATTERN.matcher(text);
        if (match.find()) {
            int year = Integer.parseInt(match.group());
            calendar.add(Calendar.YEAR, -year);
            flag[0] = true;
        }

        match = YEAR_AFTER_PATTERN.matcher(text);
        if (match.find()) {
            int year = Integer.parseInt(match.group());
            calendar.add(Calendar.YEAR, year);
            flag[0] = true;
        }
//        if (!relative.equals(calendar.getTime())) {
//            arr[0] = calendar.get(Calendar.YEAR);
//            arr[1] = calendar.get(Calendar.MONTH) + 1;
//            arr[2] = calendar.get(Calendar.DAY_OF_MONTH);
//        }
        if (flag[0] || flag[1] || flag[2] || flag[3] || flag[4]) {
            arr[0] = calendar.get(Calendar.YEAR);
        }
        if (flag[1] || flag[2] || flag[3] || flag[4]) {
            arr[1] = calendar.get(Calendar.MONTH) + 1;
        }
        if (flag[2] || flag[3] || flag[4]) {
            arr[2] = calendar.get(Calendar.DAY_OF_MONTH);
        }
        if (flag[3] || flag[4]) {
            arr[3] = calendar.get(Calendar.HOUR_OF_DAY);
        }
        if (flag[4]) {
            arr[4] = calendar.get(Calendar.MINUTE);
        }
    }

    private static final Pattern CYCLE_UNIT_PATTERN = Pattern.compile("((每天)|(每周[1-7])|(每月[1-31]号?)|每年)");

    public String parseCycle(String text) {
        Matcher match = CYCLE_UNIT_PATTERN.matcher(text);
        if (match.find()) {
            return match.group();
        }
        return null;
    }


    private static final Pattern LAST_MONTH_PATTERN = Pattern.compile("上(个)?月");
    private static final Pattern THIS_MONTH_PATTERN = Pattern.compile("(本|这个)月");
    private static final Pattern NEXT_MONTH_PATTERN = Pattern.compile("下(个)?月");
    private static final Pattern DAY_BEFORE_YESTERDAY_PATTERN = Pattern.compile("(?<!大)前天");
    private static final Pattern TODAY_PATTERN = Pattern.compile("今(?!年)");
    private static final Pattern TOMORROW_PATTERN = Pattern.compile("明(?!年)");
    private static final Pattern DAY_AFTER_TOMORROW_PATTERN = Pattern.compile("(?<!大)后天");
    private static final Pattern BEFORE_LAST_WEEKDAY_PATTERN = Pattern.compile("(?<=(上上(周|星期)))[1-7]");
    private static final Pattern LAST_WEEKDAY_PATTERN = Pattern.compile("(?<=((?<!上)上(周|星期)))[1-7]");
    private static final Pattern NEXT_WEEKDAY_PATTERN = Pattern.compile("(?<=((?<!下)下(周|星期)))[1-7]");
    private static final Pattern NEXT_NEXT_WEEKDAY_PATTERN = Pattern.compile("(?<=(下下(周|星期)))[1-7]");
    private static final Pattern THIS_WEEKDAY_PATTERN = Pattern.compile("(?<=((?<!(上|下))(周|星期)))[1-7]");

    /**
     * 设置当前时间相关的时间表达式
     * <p>
     */
    public void parseCurrentRelative(String text, TimeZone timeZone, Date relative, int[] arr) {
        Calendar calendar = Calendar.getInstance(timeZone);
        calendar.setFirstDayOfWeek(Calendar.MONDAY);
        calendar.setTime(relative);

        boolean[] flag = {false, false, false};//观察时间表达式是否因当前相关时间表达式而改变时间

        if (text.contains("前年")) {
            calendar.add(Calendar.YEAR, -2);
            flag[0] = true;
        }

        if (text.contains("去年")) {
            calendar.add(Calendar.YEAR, -1);
            flag[0] = true;
        }

        if (text.contains("今年")) {
            calendar.add(Calendar.YEAR, 0);
            flag[0] = true;
        }

        if (text.contains("明年")) {
            calendar.add(Calendar.YEAR, 1);
            flag[0] = true;
        }

        if (text.contains("后年")) {
            calendar.add(Calendar.YEAR, 2);
            flag[0] = true;
        }

        Matcher match = LAST_MONTH_PATTERN.matcher(text);
        if (match.find()) {
            calendar.add(Calendar.MONTH, -1);
            flag[1] = true;
        }

        match = THIS_MONTH_PATTERN.matcher(text);
        if (match.find()) {
            calendar.add(Calendar.MONTH, 0);
            flag[1] = true;
        }

        match = NEXT_MONTH_PATTERN.matcher(text);
        if (match.find()) {
            calendar.add(Calendar.MONTH, 1);
            flag[1] = true;
        }

        if (text.contains("大大前天")) {
            calendar.add(Calendar.DATE, -4);
            flag[2] = true;
        } else if (text.contains("大前天")) {
            calendar.add(Calendar.DATE, -3);
            flag[2] = true;
        }

        match = DAY_BEFORE_YESTERDAY_PATTERN.matcher(text);
        if (match.find()) {
            calendar.add(Calendar.DATE, -2);
            flag[2] = true;
        }

        if (text.contains("昨")) {
            calendar.add(Calendar.DATE, -1);
            flag[2] = true;
        }

        match = TODAY_PATTERN.matcher(text);
        if (match.find()) {
            flag[2] = true;
        }

        match = TOMORROW_PATTERN.matcher(text);
        if (match.find()) {
            calendar.add(Calendar.DATE, 1);
            flag[2] = true;
        }

        match = DAY_AFTER_TOMORROW_PATTERN.matcher(text);
        if (match.find()) {
            calendar.add(Calendar.DATE, 2);
            flag[2] = true;
        }

        if (text.contains("大大后天")) {
            calendar.add(Calendar.DATE, 4);
            flag[2] = true;
        } else if (text.contains("大后天")) {
            calendar.add(Calendar.DATE, 3);
            flag[2] = true;
        }

        match = BEFORE_LAST_WEEKDAY_PATTERN.matcher(text);
        if (match.find()) {
            int week = Integer.parseInt(match.group());
            if (week == 7) { // 周日=1，周二=2，。。。， 周六=7
                week = 1;
            } else {
                week++;
            }
            calendar.add(Calendar.WEEK_OF_MONTH, -2);
            calendar.set(Calendar.DAY_OF_WEEK, week);
            flag[2] = true;
        }

        match = LAST_WEEKDAY_PATTERN.matcher(text);
        if (match.find()) {
            int week = Integer.parseInt(match.group());
            if (week == 7) {
                week = 1;
            } else {
                week++;
            }
            calendar.add(Calendar.WEEK_OF_MONTH, -1);
            calendar.set(Calendar.DAY_OF_WEEK, week);
            flag[2] = true;
        }

//        rule = "(?<=((?<!下)下(周|星期)))[1-7]";
        match = NEXT_WEEKDAY_PATTERN.matcher(text);
        if (match.find()) {
            int week = Integer.parseInt(match.group());
            if (week == 7) {
                week = 1;
            } else {
                week++;
            }
            calendar.add(Calendar.WEEK_OF_MONTH, 1);
            calendar.set(Calendar.DAY_OF_WEEK, week);
            flag[2] = true;

        }

//        rule = "(?<=(下下(周|星期)))[1-7]";
        match = NEXT_NEXT_WEEKDAY_PATTERN.matcher(text);
        if (match.find()) {
            int week = Integer.parseInt(match.group());
            if (week == 7) {
                week = 1;
            } else {
                week++;
            }
            calendar.add(Calendar.WEEK_OF_MONTH, 2);
            calendar.set(Calendar.DAY_OF_WEEK, week);
            flag[2] = true;

        }

//        rule = "(?<=((?<!(上|下))(周|星期)))[1-7]";
        match = THIS_WEEKDAY_PATTERN.matcher(text);
        if (match.find()) {
            int week = Integer.parseInt(match.group());
            if (week == 7) {
                week = 1;
            } else {
                week++;
            }
//            calendar.add(Calendar.WEEK_OF_MONTH, 0);
            calendar.set(Calendar.DAY_OF_WEEK, week);
            flag[2] = true;
        }
//        if (!relative.equals(calendar.getTime()) || text.contains("今")) {
//            arr[0] = calendar.get(Calendar.YEAR);
//            arr[1] = calendar.get(Calendar.MONTH) + 1;
//            arr[2] = calendar.get(Calendar.DAY_OF_MONTH);
//        }
//        String s = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(calendar.getTime());
        if (flag[0] || flag[1] || flag[2]) {
            arr[0] = calendar.get(Calendar.YEAR);
        }
        if (flag[1] || flag[2]) {
            arr[1] = calendar.get(Calendar.MONTH) + 1;
        }
        if (flag[2]) {
            arr[2] = calendar.get(Calendar.DAY_OF_MONTH);
        }
    }
}
