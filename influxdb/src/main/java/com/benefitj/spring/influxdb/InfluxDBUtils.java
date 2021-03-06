package com.benefitj.spring.influxdb;

import com.benefitj.spring.influxdb.dto.InfluxFieldKey;
import com.benefitj.spring.influxdb.convert.LineProtocolConverter;
import com.benefitj.spring.influxdb.convert.LineProtocolConverterFactory;
import com.benefitj.spring.influxdb.convert.PointConverter;
import com.benefitj.spring.influxdb.convert.PointConverterFactory;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;
import org.influxdb.dto.QueryResult;
import org.influxdb.impl.TimeUtil;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * InfluxDB工具
 */
public class InfluxDBUtils {

  /**
   * point 转换器工厂
   */
  private static final PointConverterFactory POINT_FACTORY = PointConverterFactory.INSTANCE;
  /**
   * line protocol 转换器工厂
   */
  private static final LineProtocolConverterFactory LINE_FACTORY = LineProtocolConverterFactory.INSTANCE;

  /**
   * 获取point转换器
   *
   * @param type 类型
   * @param <T>  原对象类型
   * @return 返回行协议转换器
   */
  public static <T> PointConverter<T> getPointConverter(Class<T> type) {
    return POINT_FACTORY.getConverter(type);
  }

  /**
   * 获取行协议转换器
   *
   * @param type 类型
   * @param <T>  原对象类型
   * @return 返回行协议转换器
   */
  public static <T> LineProtocolConverter<T> getLineProtocolConverter(Class<T> type) {
    return LINE_FACTORY.getConverter(type);
  }

  /**
   * 转换成行协议数据
   *
   * @param item 原对象
   * @return 返回 line protocol String
   */
  @SuppressWarnings("all")
  static String convert(Object item) {
    LineProtocolConverter converter = getLineProtocolConverter(item.getClass());
    return converter.convert(item);
  }

  /**
   * 转换成行协议数据
   *
   * @param payload 原对象
   * @return 返回 line protocol String
   */
  @SuppressWarnings("all")
  public static List<String> toLineProtocol(Object payload) {
    if (payload instanceof CharSequence) {
      return Collections.singletonList(payload.toString());
    } else if (payload instanceof Point) {
      return Collections.singletonList(((Point) payload).lineProtocol());
    } else if (payload instanceof Collection) {
      return toLineProtocol((Collection) payload);
    } else {
      return Collections.singletonList(convert(payload));
    }
  }

  /**
   * 转换成行协议数据
   *
   * @param items 目标对象集合
   * @return 返回 line protocol String
   */
  public static List<String> toLineProtocol(Collection<?> items) {
    return items.stream()
        .map(InfluxDBUtils::convert)
        .collect(Collectors.toList());
  }

  /**
   * 转换成行协议数据
   *
   * @param points Point list
   * @return 返回 line protocol string
   */
  public static String toLineProtocol(List<Point> points) {
    return toLineProtocol(points, null);
  }

  /**
   * 转换成行协议数据
   *
   * @param points    Point list
   * @param precision 时间片单位
   * @return 返回 line protocol string
   */
  public static String toLineProtocol(List<Point> points, TimeUnit precision) {
    final BatchPoints ops = BatchPoints.database(null)
        .precision(precision)
        .build();
    points.forEach(ops::point);
    return ops.lineProtocol();
  }

  /**
   * 转换成 Point
   *
   * @param result    query result
   * @param fieldKeys field keys
   * @return return Point
   */
  public static List<Point> toPoint(QueryResult result, Map<String, InfluxFieldKey> fieldKeys) {
    return result.getResults()
        .stream()
        .flatMap(r -> {
          List<QueryResult.Series> series = r.getSeries();
          return series != null ? series.stream() : Stream.empty();
        })
        .flatMap(series -> {
          List<String> columns = series.getColumns();
          for (int i = 0; i < columns.size(); i++) {
            final InfluxFieldKey fieldKey = fieldKeys.get(columns.get(i));
            if (fieldKey == null) {
              throw new IllegalArgumentException("not found column[\"" + columns.get(i) + "\"]");
            }
            fieldKey.setIndex(i);
          }
          final List<Point> points = new LinkedList<>();
          List<List<Object>> values = series.getValues();
          for (List<Object> value : values) {
            points.add(toPoint(series.getName(), fieldKeys, value));
          }
          return points.stream();
        })
        .collect(Collectors.toList());
  }

  /**
   * 转换成 Point
   *
   * @param measurement MEASUREMENT
   * @param fieldKeys   field keys
   * @param values      values
   * @return return Point
   */
  public static Point toPoint(String measurement, Map<String, InfluxFieldKey> fieldKeys, List<Object> values) {
    final Point.Builder builder = Point.measurement(measurement);
    for (InfluxFieldKey fieldKey : fieldKeys.values()) {
      String column = fieldKey.getColumn();
      Object value = values.get(fieldKey.getIndex());
      if (fieldKey.isTimestamp()) {
        builder.time(TimeUtil.fromInfluxDBTimeFormat((String) value), TimeUnit.MILLISECONDS);
      } else {
        if (fieldKey.isTag()) {
          if (value != null) {
            builder.tag(column, (String) value);
          }
        } else {
          if (value instanceof Number) {
            builder.addField(column, fieldKey.getNumber(value));
          } else if (value instanceof CharSequence) {
            builder.addField(column, value.toString());
          } else if (value instanceof Boolean) {
            builder.addField(column, (Boolean) value);
          }
        }
      }
    }
    return builder.build();
  }

  /**
   * 是否不为空
   */
  public static boolean isNotBlank(final CharSequence cs) {
    return !isBlank(cs);
  }

  /**
   * 是否为空
   */
  public static boolean isBlank(final CharSequence cs) {
    int strLen;
    if (cs == null || (strLen = cs.length()) == 0) {
      return true;
    }
    for (int i = 0; i < strLen; i++) {
      if (!Character.isWhitespace(cs.charAt(i))) {
        return false;
      }
    }
    return true;
  }

}
