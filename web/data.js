/*
 * What the home screen shows, kept in localStorage so it opens with numbers:
 *  - the picks: titles from the age band's vetted kids catalog (catalog.js, from app/assets/catalog), only those on
 *    the streaming apps on this TV (TMDB, with the person's own free key from config.properties), three per tab, a new set every 45 s;
 *  - weather, sunrise and sunset (Open-Meteo, every 30 min).
 * Settings changed on the TV (Prefs) win over config.properties, as on Android. Built without a city (--generic), the
 * TV's time zone gives the city, and its country the region (zones.js).
 */
var CONFIG = window.CONFIG || {};

var Place = (function () {
  'use strict';

  /** A time zone's main city: {country, lat, lon, name}, or null. */
  function zone(name) {
    var all = window.ZONES || {}, v = all[name], hops = 0;
    while (v && v.charAt(0) === '=' && hops++ < 3) { name = v.slice(1); v = all[name]; }
    if (!v) return null;
    var p = v.split(',');
    return {country: p[0], lat: p[1], lon: p[2], name: p[3] || name.slice(name.lastIndexOf('/') + 1).replace(/_/g, ' ')};
  }

  var tz = zone(TV.zone()), set = CONFIG.latitude !== '' && CONFIG.latitude !== undefined;
  var country = (CONFIG.tmdbRegion || (tz ? tz.country : '') || 'US').toUpperCase();
  return {
    lat: set ? CONFIG.latitude : tz ? tz.lat : '40.7128',
    lon: set ? CONFIG.longitude : tz ? tz.lon : '-74.0060',
    city: CONFIG.city || (!set && tz ? tz.name : ''),
    country: country,
    /** Fahrenheit for TVs in the US (by their time zone, or their language when it doesn't tell). */
    fahrenheit: tz ? tz.country === 'US' : /-US$/i.test(navigator.language || '')
  };
})();

var Prefs = (function () {
  'use strict';
  var KEY = 'mykidstv.prefs';
  var v = {};
  try { v = JSON.parse(localStorage.getItem(KEY)) || {}; } catch (e) {}
  function save() { try { localStorage.setItem(KEY, JSON.stringify(v)); } catch (e) {} }
  function pick(name, fallback) { return v[name] === undefined ? fallback : v[name]; }
  return {
    version: 0,
    language: function () { return pick('language', CONFIG.language || 'auto'); },
    temperature: function () { return pick('temperature', CONFIG.temperature || 'auto'); },
    theme: function () { return pick('theme', CONFIG.theme || 'dark'); },
    weather: function () { return pick('weather', true); },
    /** The weather card's forecast: 'hours' (the next 6 hours) or 'days' (the next 5 days). */
    forecast: function () { return pick('forecast', 'days') === 'hours' ? 'hours' : 'days'; },   // the week by default
    shelf: function () { return pick('shelf', null); },
    /** The shelf_apps of the build that last set the shelf, so a new one wins once over the TV's choice. */
    shelfConfig: function () { return pick('shelfConfig', ''); },
    set: function (name, value) { v[name] = value; save(); this.version++; }
  };
})();

var Data = (function () {
  'use strict';

  function store(key, value) { try { localStorage.setItem('mykidstv.' + key, JSON.stringify(value)); } catch (e) {} }
  function stored(key) { try { return JSON.parse(localStorage.getItem('mykidstv.' + key)); } catch (e) { return null; } }

  /** GET a JSON document; done(error, json). */
  function get(url, headers, done) {
    var x = new XMLHttpRequest();
    var over = false;
    x.open('GET', url, true);
    x.timeout = 10000;
    for (var h in headers) if (headers.hasOwnProperty(h)) x.setRequestHeader(h, headers[h]);
    x.onreadystatechange = function () {
      if (x.readyState !== 4 || over) return;
      over = true;
      if (x.status === 0) { done(offline()); return; }   // reset or refused before an answer
      if (x.status !== 200) { done(new Error(x.status === 401 ? 'TMDB rejected the key' : 'HTTP ' + x.status)); return; }
      var json;
      try { json = JSON.parse(x.responseText); } catch (e) { done(e); return; }
      done(null, json);
    };
    x.ontimeout = x.onerror = function () { if (!over) { over = true; done(offline()); } };
    x.send();
  }

  /** A network error (no answer at all), which tmdb() retries. */
  function offline() {
    var e = new Error('no connection');
    e.network = true;
    return e;
  }

  var listeners = [];
  function changed() { for (var i = 0; i < listeners.length; i++) listeners[i](); }

  // MARK: - Weather

  var PLACE = Place.lat + ',' + Place.lon;
  // Both forecasts in one answer, the next 48 hours and today plus 5 days: Home Settings → Forecast switches without
  // asking again, and offline too.
  var QUERY = '&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset,weather_code'
      + '&hourly=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=6&forecast_hours=48';
  var weather = null, weatherAt = 0, fetchingWeather = false;
  (function () {
    var w = stored('weather');
    // Fetched with another query (an older version): shown, and fetched again now.
    if (w && w.place === PLACE) { weather = parseWeather(w.body); weatherAt = w.query === QUERY ? w.at : 0; }
  })();

  /** "2026-10-20T18:31" at the location (Open-Meteo's local time) → epoch ms. */
  function localTime(iso, offsetSeconds) {
    var m = /^(\d+)-(\d+)-(\d+)T(\d+):(\d+)/.exec(iso || '');
    if (!m) return 0;
    return Date.UTC(+m[1], m[2] - 1, +m[3], +m[4], +m[5]) - offsetSeconds * 1000;
  }

  function parseWeather(o) {
    if (!o || !o.current) return null;
    var off = o.utc_offset_seconds || 0, dl = o.daily, now = Date.now();
    var w = {
      temp: o.current.temperature_2m, code: o.current.weather_code, isDay: o.current.is_day !== 0,
      max: dl.temperature_2m_max[0], min: dl.temperature_2m_min[0],
      sunrise: localTime(dl.sunrise[0], off), sunset: localTime(dl.sunset[0], off), hours: [], days: []
    };
    var hr = o.hourly;
    for (var i = 0; hr && i < hr.time.length && w.hours.length < 6; i++) {
      var at = localTime(hr.time[i], off);
      if (at <= now) continue;
      w.hours.push({at: at, temp: hr.temperature_2m[i], code: hr.weather_code[i], day: !hr.is_day || hr.is_day[i] !== 0});
    }
    // The next 5 days, from tomorrow (none in a forecast cached before the days). Only days that haven't started at
    // the city: today, and past days in a forecast cached offline, are left out.
    for (var j = 0; dl.weather_code && j < dl.time.length && w.days.length < 5; j++) {
      if (localTime(dl.time[j] + 'T12:00', off) - 12 * 3600000 <= now) continue;
      var p = dl.time[j].split('-');   // named by its noon on the TV's clock, right even with the city far away
      w.days.push({at: new Date(+p[0], p[1] - 1, +p[2], 12).getTime(), max: dl.temperature_2m_max[j],
        min: dl.temperature_2m_min[j], code: dl.weather_code[j]});
    }
    return w;
  }

  function refreshWeather() {
    var now = Date.now();
    if (fetchingWeather || (now - weatherAt < 30 * 60000 && now >= weatherAt)) return;
    fetchingWeather = true;
    var url = 'https://api.open-meteo.com/v1/forecast?latitude=' + Place.lat + '&longitude=' + Place.lon + QUERY;
    get(url, {}, function (err, body) {
      fetchingWeather = false;
      if (err) return;
      weather = parseWeather(body);
      weatherAt = Date.now();
      store('weather', {place: PLACE, body: body, at: weatherAt, query: QUERY});
      changed();
    });
  }

  // MARK: - Picks (TMDB)

  var KEY = (CONFIG.tmdbKey || '').trim();
  var REGION = Place.country;
  var API = 'https://api.themoviedb.org/3';

  /** Service name on TMDB (part of it, lowercase) → our key, how to find its app on the TV (id, name), its name. */
  var SERVICES = [
    ['netflix', 'netflix', /^netflix$/, /netflix/i, 'Netflix'],
    ['prime video', 'prime', /^amazon$/, /prime video|amazon/i, 'Prime Video'],
    ['disney', 'disney', /disney/, /disney/i, 'Disney+'],
    ['hulu', 'hulu', /^hulu/, /hulu/i, 'Hulu'],
    ['paramount', 'paramount', /paramount|cbs/, /paramount/i, 'Paramount+'],
    ['peacock', 'peacock', /peacock/, /peacock/i, 'Peacock'],
    ['globoplay', 'globoplay', /globoplay/, /globoplay/i, 'Globoplay'],
    ['max', 'max', /hbo|wbd|\.max/, /^(hbo )?max$|hbo max/i, 'Max'],
    ['apple tv', 'appletv', /appletv|apple\.tv/, /apple tv/i, 'Apple TV'],
    ['hotstar', 'hotstar', /hotstar/, /hotstar/i, 'JioHotstar'],
    ['crunchyroll', 'crunchyroll', /crunchyroll/, /crunchyroll/i, 'Crunchyroll'],
    ['now tv', 'now', /nowtv|now\.tv/, /^now( tv)?$/i, 'NOW']
  ];

  var installed = [];   // the TV's apps, from TV.apps()

  /** The installed app for a service key, or null. */
  function appFor(key) {
    for (var s = 0; s < SERVICES.length; s++) {
      if (SERVICES[s][1] !== key) continue;
      for (var i = 0; i < installed.length; i++) {
        if (SERVICES[s][2].test(installed[i].id) || SERVICES[s][3].test(installed[i].title)) return installed[i];
      }
    }
    return null;
  }

  /** "… Amazon Channel" is sold inside another app; Apple TV is only the streaming service (350; 2 is the store). */
  function service(providerName, providerId) {
    var n = (providerName || '').toLowerCase();
    if (n.indexOf(' channel') >= 0) return null;
    for (var i = 0; i < SERVICES.length; i++) {
      var s = SERVICES[i];
      if (s[1] === 'appletv' && providerId !== 350) continue;
      if (s[0] === 'max' ? /\bmax\b/.test(n) : n.indexOf(s[0]) >= 0) return s;
    }
    return null;
  }

  /** Some ISPs (India's among them) reset about half of the connections to TMDB: network errors are retried. */
  var TRIES = 6;

  function tmdb(path, done) {
    var bearer = KEY.indexOf('eyJ') === 0;   // read access token (v4) or API key (v3)
    var url = API + path + (bearer ? '' : (path.indexOf('?') >= 0 ? '&' : '?') + 'api_key=' + encodeURIComponent(KEY));
    var headers = bearer ? {Authorization: 'Bearer ' + KEY, Accept: 'application/json'} : {Accept: 'application/json'};
    (function attempt(n) {
      get(url, headers, function (err, json) {
        if (err && err.network && n < TRIES) { setTimeout(function () { attempt(n + 1); }, 300 * n); return; }
        done(err, json);
      });
    })(1);
  }

  var cache = stored('recs') || {};
  var picks = cache.picks || [], listAt = cache.listAt || 0, period = cache.period, listLang = cache.lang || '', stamp = cache.stamp || '';
  var fetchingRecs = false, failedAt = 0, recsError = '';

  function saveRecs() { store('recs', {picks: picks, listAt: listAt, period: period, lang: listLang, stamp: stamp}); }

  function hourPeriod(d) {
    var h = d.getHours();
    return h >= 5 && h < 12 ? 0 : h >= 12 && h < 18 ? 1 : 2;
  }

  function sourceStamp() {
    var s = KEY.length + ':' + KEY.slice(-6) + ':' + REGION + ':' + BAND + ':' + CATALOG.length
        + (CONFIG.hideAnimation ? ':no-animation' : '');
    for (var i = 0; i < SERVICES.length; i++) s += appFor(SERVICES[i][1]) ? '1' : '0';
    return s;
  }

  function franchise(title) {
    var t = (title || '').toLowerCase(), cut = t.indexOf(':');
    if (cut < 0) cut = t.indexOf(' - ');
    return (cut > 0 ? t.slice(0, cut) : t).replace(/^\s+|\s+$/g, '');
  }

  var SPOT_EVERY = 10000, ROTATE_EVERY = 3 * SPOT_EVERY;   // the window moves on every 10 s; a new set after all 3

  /** This set's 3 picks (a new set every ROTATE_EVERY): movies ("movie"), shows ("tv") or both (null). */
  function now(kind) {
    return at(kind, Math.floor(Date.now() / ROTATE_EVERY));
  }

  /**
   * The 3 for one tab in rotation slot `slot`, as on Android. The tabs never share a title: in each slot Movies takes
   * the next 3 movies, Shows the next 3 shows and Explore the 2 movies and 1 show after those.
   */
  function at(kind, slot) {
    var movies = [], shows = [], out = [], seen = {};
    if (!KEY) return out;
    for (var i = 0; i < picks.length; i++) {
      var p = picks[i];
      if (!p.detailed || (p.app && appFor(p.app))) (p.kind === 'movie' ? movies : shows).push(p);
    }
    if (kind === 'movie') take(out, seen, movies, slot * 3, 3);
    else if (kind === 'tv') take(out, seen, shows, slot * 3, 3);
    else {
      // What Movies and Shows show this slot, so Explore skips them (and their franchises).
      take([], seen, movies, slot * 3, 3);
      take([], seen, shows, slot * 3, 3);
      var mixed = [];
      take(mixed, copy(seen), movies, slot * 3 + 3, 2);
      take(mixed, copy(seen), shows, slot * 3 + 3, 1);
      for (var j = 0; j < mixed.length && out.length < 3; j++) {
        var f = franchise(mixed[j].title);
        if (!seen[f]) { seen[f] = true; out.push(mixed[j]); }
      }
      if (out.length < 3) take(out, seen, movies.concat(shows), slot * 3, 3 - out.length);   // a small list
    }
    return out;
  }

  /** Adds up to n from list, starting at `from` (wrapping), never two from the same franchise or one in `seen`. */
  function take(out, seen, list, from, n) {
    for (var i = 0, added = 0; i < list.length && added < n; i++) {
      var q = list[(from + i) % list.length], f = franchise(q.title);
      if (!seen[f]) { seen[f] = true; out.push(q); added++; }
    }
  }

  function copy(o) {
    var c = {};
    for (var k in o) if (o.hasOwnProperty(k)) c[k] = true;
    return c;
  }

  /** TMDB's language, as on Android: Latin American Spanish unless the TV is in Spain, and Germany's German. */
  function lang() {
    if (I18N.lang === 'es') return /-ES\b/i.test(navigator.language || '') ? 'es-ES' : 'es-MX';
    if (I18N.lang === 'de') return 'de-DE';   // TMDB's de-AT and de-CH have few translations of their own
    return I18N.locale();
  }

  /** TMDB's genres that it leaves in English in some languages. */
  function genre(name) {
    switch (name) {
      case 'Action & Adventure': return tr('genre|Action & Adventure');
      case 'Sci-Fi & Fantasy': return tr('genre|Sci-Fi & Fantasy');
      case 'War & Politics': return tr('genre|War & Politics');
      case 'Kids': return tr('genre|Kids');
      case 'News': return tr('genre|News');
      case 'Soap': return tr('genre|Soap');
      case 'Talk': return tr('genre|Talk');
      default: return name;
    }
  }

  /** The best backdrop: no text over it, the largest, then the best rated. */
  function bestBackdrop(images) {
    var list = images && images.backdrops, best = null, score = -1;
    for (var i = 0; list && i < list.length; i++) {
      var b = list[i];
      if (!b.file_path) continue;
      var s = (b.iso_639_1 === null ? 1e7 : 0) + Math.min(b.width || 0, 3840) * 100 + (b.vote_average || 0);
      if (s > score) { score = s; best = b.file_path; }
    }
    return best;
  }

  /** Genre, runtime or seasons, and the service it's on among the TV's apps. */
  function details(p, done) {
    tmdb('/' + p.kind + '/' + p.id + '?language=' + lang() + '&append_to_response=watch/providers,images&include_image_language=null,'
        + I18N.base + ',en', function (err, o) {
      if (err) { done(err); return; }
      var best = bestBackdrop(o.images);
      if (best) p.backdrop = best;
      else if (!p.backdrop && o.backdrop_path) p.backdrop = o.backdrop_path;
      p.genreIds = [];
      for (var g = 0; o.genres && g < o.genres.length; g++) p.genreIds.push(o.genres[g].id);
      var parts = [];
      if (p.kind === 'tv') parts.push(tr('Series'));
      if (o.genres && o.genres.length) {
        parts.push(genre(o.genres[0].name));
      }
      if (p.kind === 'movie' && o.runtime > 0) {
        var h = Math.floor(o.runtime / 60), m = o.runtime % 60;
        parts.push(h ? trf('{h}h {m}m', {h: h, m: m}) : trf('{m}m', {m: m}));
      } else if (p.kind === 'tv' && o.number_of_seasons > 0) {
        parts.push(plural('plural|one:{n} season|other:{n} seasons', o.number_of_seasons));
      }
      var wp = o['watch/providers'], here = wp && wp.results && wp.results[REGION], flat = here && here.flatrate;
      p.app = null;
      for (var i = 0; flat && i < flat.length && !p.app; i++) {
        var s = service(flat[i].provider_name, flat[i].provider_id);
        if (s && appFor(s[1])) { p.app = s[1]; p.service = s[4]; }
      }
      if (p.service && p.app) parts.push(p.service);
      p.meta = parts.join(' · ');
      p.tagline = storyLine(o);
      title(p, o, function () {
        p.detailed = true;
        done(null);
      });
    });
  }

  /** The story in one line for the window: TMDB's tagline, or the synopsis's first sentence when it's short. */
  function storyLine(o) {
    var t = (o.tagline || '').replace(/^\s+|\s+$/g, '');
    if (t) return t;
    var first = (o.overview || '').split(/(?:[.!?。])\s/)[0];
    return first && first.length <= 120 ? first.replace(/[.!?。]?$/, '.') : '';
  }

  /**
   * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
   * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
   */
  function title(p, o, done) {
    var field = p.kind === 'movie' ? 'title' : 'name', own = o[field] || p.title;
    p.title = own;
    if (I18N.lang === 'en' || !own || own !== o['original_' + field] || o.original_language === I18N.base
        || o.original_language === 'en') { done(); return; }
    tmdb('/' + p.kind + '/' + p.id + '?language=en-US', function (err, en) {
      if (!err && en && en[field]) p.title = en[field];
      done();
    });
  }

  /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
  var ANIMATION = 16;

  function animated(o) {
    for (var i = 0; o.genre_ids && i < o.genre_ids.length; i++) if (o.genre_ids[i] === ANIMATION) return true;
    return false;
  }

  function addPick(list, o, kind) {
    if (!o.backdrop_path) return;
    if (CONFIG.hideAnimation && animated(o)) return;
    list.push({id: o.id, kind: kind, title: kind === 'movie' ? o.title : o.name, backdrop: o.backdrop_path, meta: '', app: null, detailed: false});
  }

  function keyOf(p) { return p.kind + ':' + p.id; }

  function alternate(out, seen, a, b, max) {
    for (var i = 0; i < Math.max(a.length, b.length) && out.length < max; i++) {
      if (i < a.length && !seen[keyOf(a[i])]) { seen[keyOf(a[i])] = true; out.push(a[i]); }
      if (i < b.length && out.length < max && !seen[keyOf(b[i])]) { seen[keyOf(b[i])] = true; out.push(b[i]); }
    }
  }

  /** Runs fn on each item one after the other (the TV's network is shared with what's playing). */
  function each(items, fn, done) {
    var i = 0;
    (function next() {
      if (i >= items.length) { done(); return; }
      fn(items[i++], next);
    })();
  }

  // MARK: - Kids catalog

  /**
   * The child's age band (age_band in config.properties) and its vetted titles (catalog.js, from
   * app/assets/catalog/<band>.json: TMDB age ratings, Jev's judgments and a person's review). A band without a catalog
   * yet has none: the age-restricted fallback below fills the list then.
   */
  var BAND = /^(4-6|7-10|11-13)$/.test(CONFIG.ageBand || '') ? CONFIG.ageBand : '7-10';
  var CATALOG = (window.CATALOG && window.CATALOG.band === BAND && window.CATALOG.titles) || [];
  var MOVIES = 40, SHOWS = 20, ENOUGH = 12, HISTORY_MAX = 240;

  /** The titles shown lately ("movie:123"), oldest first, so a new list starts with others. */
  function history() {
    var h = stored('history');
    return h && h.length !== undefined ? h : [];
  }

  /** Remembers the newest HISTORY_MAX titles shown, the oldest dropped first. */
  function remember(list) {
    var h = history(), out = [], seen = {}, i;
    for (i = 0; i < list.length; i++) seen[keyOf(list[i])] = true;
    for (i = 0; i < h.length; i++) if (!seen[h[i]]) out.push(h[i]);
    for (i = 0; i < list.length; i++) out.push(keyOf(list[i]));
    store('history', out.slice(Math.max(0, out.length - HISTORY_MAX)));
  }

  function shuffle(a) {
    for (var i = a.length - 1; i > 0; i--) {
      var j = Math.floor(Math.random() * (i + 1)), t = a[i];
      a[i] = a[j];
      a[j] = t;
    }
    return a;
  }

  /** A new order each time, the best (Jev's score) early more often: random^(1/score^4), highest first. */
  function weightedShuffle(a) {
    for (var i = 0; i < a.length; i++) a[i]._draw = Math.pow(Math.random(), 1 / Math.pow(Math.max(0.05, a[i].score || 0.7), 4));
    a.sort(function (x, y) { return y._draw - x._draw; });
    return a;
  }

  function hasGenre(ids, genre) {
    for (var i = 0; ids && i < ids.length; i++) if (ids[i] === genre) return true;
    return false;
  }

  /**
   * Up to `max` catalog titles of this kind that are on one of the TV's apps (the details say where), the ones not
   * shown lately first, in a new order each time that favors the best (Jev's score), with at most a couple of the
   * famous ones (nearly every family has seen them) mixed in. Looks at most max * 6 of them up.
   */
  function handPicked(kind, max, shown, done) {
    var fresh = [], old = [], famous = [], out = [];
    var watched = stored('watched') || [];
    for (var i = 0; i < CATALOG.length; i++) {
      var t = CATALOG[i];
      if (t.kind !== kind || watched.indexOf(kind + ':' + t.id) >= 0) continue;
      if (t.famous) { if (!shown[kind + ':' + t.id]) famous.push(t); continue; }
      (shown[kind + ':' + t.id] ? old : fresh).push(t);
    }
    weightedShuffle(fresh);
    weightedShuffle(famous);
    // Not shown lately first, then the ones shown lately, so the list still fills (the history must never hide
    // everything the TV's apps actually have).
    var order = fresh.concat(weightedShuffle(old));
    var few = kind === 'movie' ? 2 : 1;
    for (var f = 0; f < Math.min(few, famous.length); f++) {
      order.splice(Math.floor(Math.random() * (Math.min(order.length, max) + 1)), 0, famous[f]);
    }
    if (order.length < max) order = order.concat(famous.slice(few));   // a small catalog
    order = order.slice(0, max * 6);
    var i2 = 0;
    (function next() {
      if (out.length >= max || i2 >= order.length) { done(out); return; }
      var t = order[i2++];
      var p = {id: t.id, kind: kind, title: t.title, backdrop: null, meta: '', app: null, detailed: false, badge: t.badge || ''};
      details(p, function (err) {
        if (!err && p.app && p.backdrop && !(CONFIG.hideAnimation && hasGenre(p.genreIds, ANIMATION))) out.push(p);
        next();
      });
    })();
  }

  function unseen(list, shown) {
    return list.filter(function (p) { return !shown[keyOf(p)]; });
  }

  function fetchList(done) {
    tmdb('/watch/providers/movie?language=' + lang() + '&watch_region=' + REGION, function (err, res) {
      if (err) { done(err); return; }
      var ids = [];
      for (var i = 0; res.results && i < res.results.length; i++) {
        var s = service(res.results[i].provider_name, res.results[i].provider_id);
        if (s && appFor(s[1])) ids.push(res.results[i].provider_id);
      }
      if (!ids.length) { done(new Error("none of the TV's streaming apps are on TMDB")); return; }
      var h = history(), shown = {};
      for (var k = 0; k < h.length; k++) shown[h[k]] = true;
      handPicked('movie', MOVIES, shown, function (movies) {
        handPicked('tv', SHOWS, shown, function (shows) {
          var list = [], seen = {};
          alternate(list, seen, movies, shows, MOVIES + SHOWS);
          // Only vetted titles, ever (no fallback from TMDB at large). A bad moment for TMDB: keep the list we have.
          if (!list.length) { done(new Error('TMDB returned no recommendations')); return; }
          if (list.length < ENOUGH && picks.length) { done(new Error('only ' + list.length + ' titles came back from TMDB')); return; }
          remember(list);
          picks = list;
          done(null);
        });
      });
    });
  }

  /** Fetches the list when stale (1 h, a new period of the day, language, key or apps), then this hour's details. */
  function refreshRecs() {
    if (!KEY || fetchingRecs || !installed.length) return;
    var t = Date.now(), p = hourPeriod(new Date()), st = sourceStamp();
    if (st === stamp && failedAt && t - failedAt < 10 * 60000 && t >= failedAt) return;
    var stale = st !== stamp || p !== period || t - listAt > 3600000 || t < listAt || picks.length < 9 || listLang !== I18N.lang;
    var wanted = all(), missing = false;
    for (var i = 0; i < wanted.length; i++) missing = missing || !wanted[i].detailed || wanted[i].tagline === undefined;
    if (!stale && !missing) return;
    fetchingRecs = true;
    stamp = st;
    function finish(err) {
      fetchingRecs = false;
      if (err) { recsError = err.message; failedAt = Date.now(); } else { recsError = ''; failedAt = 0; }
      saveRecs();
      changed();
    }
    function detailWanted() {
      var todo = all().filter(function (x) { return !x.detailed || x.tagline === undefined; });   // older saves have no story line
      each(todo, function (x, next) { details(x, function () { next(); }); }, function () { finish(null); });
    }
    if (stale) {
      fetchList(function (err) {
        if (err) { finish(err); return; }
        listAt = Date.now();
        period = p;
        listLang = I18N.lang;
        detailWanted();
      });
    } else {
      detailWanted();
    }
  }

  /** This minute's picks for the three tabs and the next minute's, without repeats (the screensaver shows them too). */
  function all() {
    var out = [], seen = {}, kinds = ['movie', 'tv'], slot = Math.floor(Date.now() / ROTATE_EVERY);
    for (var s = slot; s <= slot + 1; s++) {
      for (var k = 0; k < kinds.length; k++) {
        var list = at(kinds[k], s);
        for (var i = 0; i < list.length; i++) if (!seen[keyOf(list[i])]) { seen[keyOf(list[i])] = true; out.push(list[i]); }
      }
    }
    return out;
  }

  return {
    city: Place.city,
    onChange: function (fn) { listeners.push(fn); },
    setApps: function (apps) { installed = apps; },
    appFor: appFor,
    refresh: function () { refreshWeather(); refreshRecs(); },
    settingsChanged: function () { changed(); },
    get weather() { return weather; },
    get weatherAt() { return weatherAt; },
    hasKey: function () { return !!KEY; },
    recsError: function () { return recsError; },
    picks: now,
    /** The child opened this title from a card: never suggested again (remembered on the TV only). */
    watched: function (p) {
      var w = stored('watched') || [], k = keyOf(p);
      if (w.indexOf(k) < 0) { w.push(k); store('watched', w); }
      picks = picks.filter(function (q) { return keyOf(q) !== k; });
      saveRecs();
    },
    /** Which of the 3 the window shows while nobody is browsing (0-2), and when it moves on next. */
    spot: function () { return Math.floor((Date.now() % ROTATE_EVERY) / SPOT_EVERY); },
    showcase: all,
    image: function (path, size) { return path ? 'https://image.tmdb.org/t/p/' + (size || 'w1280') + path : ''; },

    /** Night at the location: from sunset to sunrise (the last forecast; without one, 6:00 and 18:00). */
    isNight: function (at) {
      var theme = Prefs.theme();
      if (theme === 'dark') return true;
      if (theme === 'light') return false;
      var d = new Date(at), mins = d.getHours() * 60 + d.getMinutes();
      function hm(t, fallback) { if (!t) return fallback; var x = new Date(t); return x.getHours() * 60 + x.getMinutes(); }
      var rise = hm(weather && weather.sunrise, 360), set = hm(weather && weather.sunset, 1080);
      return mins < rise || mins >= set;
    },

    degrees: function (v) {
      if (v === undefined || v === null || isNaN(v)) return '—';
      var unit = Prefs.temperature();
      var f = unit === 'F' || (unit === 'auto' && Place.fahrenheit);
      return Math.round(f ? v * 9 / 5 + 32 : v) + '°';
    },
    /** "3 PM", "15:00", "15 Uhr": the hourly forecast. */
    hour: function (at) { return formatDate('format|h a', at); },
    /** "Seg", "Mon", "Lun", "Mo", "Пн": the short day of the week, capitalized and without the abbreviation's dot. */
    weekday: function (at) {
      var s = formatDate('format|EEE', at).replace(/\.$/, '');
      return s.charAt(0).toUpperCase() + s.substring(1);
    },
    /** "5:21 AM", "05:21": the clock, sunrise and sunset. */
    time: function (at) { return formatDate('format|h:mm a', at); },
    /** "12 min ago" when the data is old (no internet), or empty. */
    staleness: function (at, every) {
      if (!at) return '';
      var age = Date.now() - at;
      if (age < every * 2) return '';
      var m = Math.floor(age / 60000), h = Math.floor(m / 60);
      return m < 60 ? trf('{n} min ago', {n: m}) : trf('{n} h ago', {n: h});
    },
    condition: function (code, day) {
      if (code === undefined || code === null || code < 0) return '';
      if (code === 0) return day ? tr('Clear') : tr('night|Clear');
      if (code === 1) return day ? tr('Mostly Sunny') : tr('Mostly Clear');
      if (code === 2) return tr('Partly Cloudy');
      if (code === 3) return tr('Cloudy');
      if (code === 45 || code === 48) return tr('Fog');
      if (code >= 51 && code <= 57) return tr('Drizzle');
      if (code === 65 || code === 67 || code === 82) return tr('Heavy Rain');
      if ((code >= 71 && code <= 77) || code === 85 || code === 86) return tr('Snow');
      if (code >= 95) return tr('Thunderstorms');
      return tr('Rain');
    },
    symbol: function (code, day) {
      if (code <= 1) return day ? 'sunny' : 'clear_night';
      if (code === 2) return day ? 'partly_cloudy_day' : 'partly_cloudy_night';
      if (code === 3) return 'cloud';
      if (code === 45 || code === 48) return 'foggy';
      if ((code >= 71 && code <= 77) || code === 85 || code === 86) return 'snowflake';
      if (code >= 95) return 'thunderstorm';
      return 'rainy';
    }
  };
})();
