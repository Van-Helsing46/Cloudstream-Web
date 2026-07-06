import { MediaCard } from "./MediaCard";
import type { HomeSection } from "../types";

/** Home section: title + horizontally scrollable rail of cards. */
export function SectionRail({ section }: { section: HomeSection }) {
  if (section.items.length === 0) return null;
  return (
    <section className="rail">
      <h2 className="rail-title">{section.title}</h2>
      <div className="rail-track">
        {section.items.map((item, i) => (
          <MediaCard
            key={`${item.id}-${i}`}
            item={item}
            horizontal={section.isHorizontal}
          />
        ))}
      </div>
    </section>
  );
}
