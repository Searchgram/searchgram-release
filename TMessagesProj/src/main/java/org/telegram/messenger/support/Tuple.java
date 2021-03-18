package org.telegram.messenger.support;

public class Tuple<T0, T1, T2> {
	public T0 _0;
	public T1 _1;
	public T2 _2;

	public static <T0, T1> Tuple<T0, T1, Void> pair(T0 _0, T1 _1) {
		return triple(_0, _1, null);
	}

	public static <T0, T1, T2> Tuple<T0, T1, T2> triple(T0 _0, T1 _1, T2 _2) {
		Tuple<T0, T1, T2> tuple = new Tuple<>();
		tuple._0 = _0;
		tuple._1 = _1;
		tuple._2 = _2;
		return tuple;
	}
}
