import { useState } from "react";
import { Link } from "react-router-dom";
import { useQueries, useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { SectionRail } from "../components/SectionRail";
import { ResumeCard } from "../components/ResumeCard";
import { useT } from "../i18n";
import { posterGradient } from "../lib/colors";
import { matchGenre, type GenreKey } from "../lib/genres";
import type { HomeResponse, HomeSection, SearchItem } from "../types";

/**
 * Merges the sections of one or more providers, round-robin, so "All" interleaves
 * sources instead of showing them one block after another. Same-titled sections from
 * different providers get the provider name appended to disambiguate them.
 */
function buildSections(responses: HomeResponse[], providerName: (id: string) => string): HomeSection[] {
  const maxLen = Math.max(0, ...responses.map((r) => r.sections.length));
  const ordered: { providerId: string; section: HomeSection }[] = [];
  for (let i = 0; i < maxLen; i++) {
    for (const r of responses) {
      const s = r.sections[i];
      if (s && s.items.length > 0) ordered.push({ providerId: r.providerId, section: s });
    }
  }
  const titleCounts = new Map<string, number>();
  for (const { section } of ordered) titleCounts.set(section.title, (titleCounts.get(section.title) ?? 0) + 1);
  return ordered.map(({ providerId, section }) =>
    (titleCounts.get(section.title) ?? 0) > 1
      ? { ...section, title: `${section.title} — ${providerName(providerId)}` }
      : section,
  );
}

/** Matches section titles like "Top 10" / "Top 10 titoli di oggi" regardless of provider wording. */
function isTop10Title(title: string): boolean {
  return /top\s*10/i.test(title);
}

/**
 * Picks the section that feeds the special "Top 10" rail (a provider's own Top 10 section
 * if one exists, otherwise the first section) and the remaining sections to render as normal
 * rails, excluding the Top 10 source and any other section that also looks like a Top 10 list.
 */
function selectHomeSections(sections: HomeSection[]): { top10Section: HomeSection | undefined; rails: HomeSection[] } {
  const top10Section = sections.find((s) => isTop10Title(s.title)) ?? sections[0];
  const rails = sections.slice(1).filter((s) => s !== top10Section && !isTop10Title(s.title));
  return { top10Section, rails };
}

/** Home: hero for the top title, source chips, continue watching, Top 10, and section rails. */
export function HomePage() {
  const t = useT();
  const providers = useQuery({ queryKey: ["providers"], queryFn: api.providers });
  const [selectedProviderId, setSelectedProviderId] = useState<string | null>(null); // null = All
  const [selectedGenre, setSelectedGenre] = useState<GenreKey | null>(null); // null = all genres

  const homeProviders = providers.data?.filter((p) => p.hasMainPage) ?? [];
  const providerName = (id: string) => providers.data?.find((p) => p.id === id)?.name ?? id;

  const singleHome = useQuery({
    queryKey: ["home", selectedProviderId],
    queryFn: () => api.home(selectedProviderId!),
    enabled: selectedProviderId !== null,
  });

  const allHomes = useQueries({
    queries:
      selectedProviderId === null
        ? homeProviders.map((p) => ({ queryKey: ["home", p.id], queryFn: () => api.home(p.id) }))
        : [],
  });

  const continueWatching = useQuery({
    queryKey: ["library", "continue"],
    queryFn: api.library.continueWatching,
  });

  if (providers.isLoading) return <p className="muted">{t("home.loadingProviders")}</p>;
  if (providers.isError) return <p className="error">{t("home.backendUnreachable")}</p>;

  if ((providers.data?.length ?? 0) === 0) {
    return (
      <>
        <h1>{t("nav.home")}</h1>
        <p className="muted">
          {t("home.emptyBefore")} <a href="/extensions">{t("nav.extensions")}</a> {t("home.emptyAfter")}
        </p>
      </>
    );
  }

  const responses: HomeResponse[] =
    selectedProviderId === null
      ? allHomes.map((q) => q.data).filter((d): d is HomeResponse => !!d)
      : singleHome.data
        ? [singleHome.data]
        : [];

  const homeLoading = selectedProviderId === null ? allHomes.some((q) => q.isLoading) : singleHome.isLoading;
  const homeFailed =
    selectedProviderId === null
      ? homeProviders.length > 0 && allHomes.every((q) => q.isError)
      : singleHome.isError;

  const sections = buildSections(responses, providerName);
  const heroItem: SearchItem | undefined = sections[0]?.items[0];
  const { top10Section, rails } = selectHomeSections(sections);
  const providerErrors = responses.filter((r): r is HomeResponse & { error: string } => !!r.error);

  // v1 genre navigation: derived from rail titles (no taxonomy from providers), see lib/genres.
  const presentGenres = [...new Set(rails.map((s) => matchGenre(s.title)).filter((g): g is GenreKey => g !== null))];
  const visibleRails = selectedGenre ? rails.filter((s) => matchGenre(s.title) === selectedGenre) : rails;

  return (
    <>
      {heroItem && <Hero item={heroItem} />}

      <div className="page-sections">
        {homeProviders.length > 1 && (
          <div className="chip-row">
            <button
              className={selectedProviderId === null ? "chip chip-active" : "chip"}
              onClick={() => setSelectedProviderId(null)}
            >
              {t("search.allProviders")}
            </button>
            {homeProviders.map((p) => (
              <button
                key={p.id}
                className={selectedProviderId === p.id ? "chip chip-active" : "chip"}
                onClick={() => setSelectedProviderId(p.id)}
              >
                {p.name}
              </button>
            ))}
          </div>
        )}

        {presentGenres.length > 0 && (
          <div className="chip-row">
            <button
              className={selectedGenre === null ? "chip chip-active" : "chip"}
              onClick={() => setSelectedGenre(null)}
            >
              {t("home.allGenres")}
            </button>
            {presentGenres.map((g) => (
              <button
                key={g}
                className={selectedGenre === g ? "chip chip-active" : "chip"}
                onClick={() => setSelectedGenre(g)}
              >
                {t(`home.genre.${g}`)}
              </button>
            ))}
          </div>
        )}

        {continueWatching.data && continueWatching.data.length > 0 && (
          <section>
            <h2 className="rail-title">{t("library.continueWatching")}</h2>
            <div className="rail-track">
              {continueWatching.data.map((h) => (
                <ResumeCard key={`${h.providerId}:${h.episodeId}`} entry={h} />
              ))}
            </div>
          </section>
        )}

        {homeLoading && sections.length === 0 && <p className="muted">{t("home.loading")}</p>}
        {homeFailed && <p className="error">{t("home.backendUnreachable")}</p>}
        {!homeLoading && !homeFailed && sections.length === 0 && (
          <p className="muted">{t("home.noSections")}</p>
        )}

        {providerErrors.length > 0 && (
          <div className="warn-banner">
            {providerErrors.map((r) => (
              <div key={r.providerId}>
                <strong>{providerName(r.providerId)}</strong>: {r.error}
              </div>
            ))}
          </div>
        )}

        {top10Section && top10Section.items.length > 0 && (
          <section>
            <h2 className="rail-title">{t("home.top10")}</h2>
            <div className="top10-rail">
              {top10Section.items.slice(0, 10).map((item, i) => (
                <Top10Card key={`${item.providerId}:${item.id}-${i}`} item={item} rank={i + 1} />
              ))}
            </div>
          </section>
        )}

        {visibleRails.map((section, i) => (
          <SectionRail key={`${section.title}-${i}`} section={section} />
        ))}
      </div>
    </>
  );
}

/** Full-bleed hero for the top title of the current section list: poster/gradient backdrop, resume/play + details. */
function Hero({ item }: { item: SearchItem }) {
  const t = useT();
  const providers = useQuery({ queryKey: ["providers"], queryFn: api.providers });
  const providerLabel = providers.data?.find((p) => p.id === item.providerId)?.name ?? item.providerId;

  // Best-effort: SearchItem has no plot, so fetch it lazily and say nothing if it fails.
  const detail = useQuery({
    queryKey: ["detail", item.providerId, item.id],
    queryFn: () => api.detail(item.providerId, item.id),
    retry: false,
  });

  const progress = useQuery({
    queryKey: ["library", "media-progress", item.providerId, item.id],
    queryFn: () => api.library.mediaProgress(item.providerId, item.id),
  });
  const resumeEntry = (progress.data ?? []).find(
    (e) => !e.durationSeconds || e.positionSeconds / e.durationSeconds < 0.9,
  );
  const resumeLabel = resumeEntry
    ? `${t("detail.resume")}${
        resumeEntry.season != null && resumeEntry.episode != null
          ? ` S${resumeEntry.season}E${resumeEntry.episode}`
          : ""
      }`
    : t("detail.play");

  const href = `/media/${item.providerId}?id=${encodeURIComponent(item.id)}`;
  const meta = [t(`mediaType.${item.type}`), item.year, providerLabel].filter(Boolean).join(" · ");

  return (
    <div
      className="home-hero"
      style={
        item.posterUrl
          ? { backgroundImage: `url("${item.posterUrl}")` }
          : { background: posterGradient(item.title) }
      }
    >
      <div className="home-hero-scrim" />
      <div className="home-hero-content">
        <span className="home-hero-meta">{meta}</span>
        <h1 className="home-hero-title">{item.title}</h1>
        {detail.data?.plot && <p className="home-hero-plot">{detail.data.plot}</p>}
        <div className="home-hero-actions">
          <Link to={href} className="btn-primary">
            ▶ {resumeLabel}
          </Link>
          <Link to={href} className="btn-secondary">
            {t("home.details")}
          </Link>
        </div>
      </div>
    </div>
  );
}

/** Top 10 rail item: oversized outlined rank number overlapping the poster. */
function Top10Card({ item, rank }: { item: SearchItem; rank: number }) {
  return (
    <Link
      to={`/media/${item.providerId}?id=${encodeURIComponent(item.id)}`}
      className="top10-card"
      title={item.title}
    >
      <span className="top10-rank">{rank}</span>
      <div className="top10-poster">
        {item.posterUrl ? (
          <img src={item.posterUrl} alt={item.title} loading="lazy" />
        ) : (
          <div className="media-card-placeholder" style={{ background: posterGradient(item.title) }}>
            <span className="media-card-placeholder-title">{item.title}</span>
          </div>
        )}
      </div>
    </Link>
  );
}
