package panel

import "time"

// dayLayout is the only date format the panel accepts or emits. Fixed and
// unlocalised on purpose: these strings go into URLs and are compared as
// strings by the browser's <input type="date">, so a localised "2026年9月1日"
// would work in exactly one place and break in the other.
const dayLayout = "2006-01-02"

// startOfDay parses "2006-01-02" to the first millisecond of that day in the
// server's zone. An unparseable date returns 0, which every filter treats as
// "no lower bound" — a typo in the URL should widen the result, not empty it
// and leave the user staring at an empty ledger thinking they lost data.
func startOfDay(s string) int64 {
	t, err := time.ParseInLocation(dayLayout, s, time.Local)
	if err != nil {
		return 0
	}
	return t.UnixMilli()
}

// startOfDayNext is the exclusive upper bound for a `to` date.
//
// Without the +1 day, asking for "up to 2026-09-30" silently drops every
// transaction on the 30th — the range reads as inclusive in the UI and is
// exclusive in the query, which is exactly the kind of off-by-one that makes a
// monthly total disagree with the phone by one day's spending.
func startOfDayNext(s string) int64 {
	t, err := time.ParseInLocation(dayLayout, s, time.Local)
	if err != nil {
		return 0
	}
	return t.AddDate(0, 0, 1).UnixMilli()
}
