"""Age bands: the hard rating filters, the TMDB sweeps and the words Jev judges with. Thresholds live here, in code."""

BANDS = {
    '4-6': {
        'child': 'a 4-to-6-year-old child',
        'us_movie_ok': {'G', 'PG'}, 'us_movie_bad': {'PG-13', 'R', 'NC-17'},
        'in_movie_ok': {'U'}, 'tv_bad': {'TV-14', 'TV-MA'},
        'too_young': "Made for babies and toddlers (under about 4): too simple for a 4-6-year-old.",
        'too_mature': "Better saved for age 7 or older: scary scenes, peril, fighting, sad or intense moments, or a plot too complex for a 4-6-year-old.",
        'movie_sweeps': [dict(with_genres=g, certification_country='US', **{'certification.lte': 'G'}) for g in (16, 10751, 12, 35)]
                        + [dict(with_genres='16|10751', certification_country='US', **{'certification.lte': 'PG'})],
        'tv_sweeps': [dict(with_genres=10762), dict(with_genres='16|10751')],
    },
    '7-10': {
        'child': 'a 7-to-10-year-old child',
        'us_movie_ok': {'G', 'PG'}, 'us_movie_bad': {'PG-13', 'R', 'NC-17'},
        'in_movie_ok': {'U', 'U/A 7+', 'UA 7+', 'UA7+', 'U/A7+'}, 'tv_bad': {'TV-Y', 'TV-14', 'TV-MA'},
        'too_young': "Made mainly for preschoolers or toddlers (about ages 2-6): simple, slow or babyish for a 7-10-year-old.",
        'too_mature': "Better saved for age 12 or older: intense violence, horror, sexual or romantic focus, strong language, very dark themes, or too complex for a 7-10-year-old.",
        'movie_sweeps': [dict(with_genres=g, certification_country='US', **{'certification.lte': 'PG'})
                         for g in (16, 12, 14, 35, 10751, 99, 878, 36, 10402, 18, 9648)],
        'tv_sweeps': [dict(with_genres=g) for g in (10762, 16, 99, 10751)],
    },
    '11-13': {
        'child': 'an 11-to-13-year-old child',
        'us_movie_ok': {'G', 'PG', 'PG-13'}, 'us_movie_bad': {'R', 'NC-17'},
        'in_movie_ok': {'U', 'UA', 'U/A', 'U/A 7+', 'UA 7+', 'U/A 13+', 'UA 13+', 'UA13+'}, 'tv_bad': {'TV-Y', 'TV-MA'},
        'too_young': "Made mainly for young children (under about 9): childish for an 11-13-year-old.",
        'too_mature': "Better saved for age 14 or older: graphic violence, horror, sexual content, heavy drug use, strong language, or very disturbing themes.",
        'movie_sweeps': [dict(with_genres=g, certification_country='US', **{'certification.lte': 'PG-13'})
                         for g in (12, 878, 14, 16, 99, 36, 18, 10402, 35, 9648, 10751)],
        'tv_sweeps': [dict(with_genres=g) for g in (10759, 10765, 16, 99, 10751, 35)],
    },
}

def questions(band):
    """Jev questions for a band: the judgment is the same, only the child's age and the age limits change."""
    b = BANDS[band]
    child = b['child']
    return {
        'age_fit': {'type': 'choice',
            'instructions': "Considering the title in `title` and its details, who is it right for, compared with %s?" % child,
            'criteria': {'too_young': b['too_young'],
                         'just_right': "Suits %s: engaging at that age, and its content is appropriate for that age." % child,
                         'too_mature': b['too_mature']}},
        'value': {'type': 'score',
            'instructions': "How much lasting value does watching the title in `title` give %s, beyond entertainment?" % child,
            'criteria': [
                "Disposable: entertaining at best, almost nothing to take away.",
                "A light positive message, but mostly generic and forgettable.",
                "Clear worthwhile takeaways: kindness, courage, friendship, perseverance, or real knowledge about the world.",
                "Rich and memorable: builds character, curiosity, empathy, or understanding of science, history, nature, art or other cultures; children remember it for years."]},
        'must_watch': {'type': 'noul',
            'instructions': "Is the title in `title` widely regarded as an outstanding, celebrated work - the kind a well-informed parent would make sure %s does not miss?" % child},
        'fun': {'type': 'score',
            'instructions': "How enjoyable and engaging is the title in `title` for %s?" % child,
            'criteria': [
                "Likely boring for most children that age: slow, dry, or aimed at others.",
                "Holds their attention in parts.",
                "Engaging and enjoyable for most children that age.",
                "Delightful: children that age typically love it and want to watch it again."]},
    }

def audience(band):
    return ("A parent is choosing titles for %s. They want every title to earn its place: genuinely fun for a child "
            "that age AND leaving real value (character, courage, kindness, curiosity, knowledge of science, history, "
            "nature, art, sport or other cultures). Not too young for them, not too mature." % BANDS[band]['child'])
