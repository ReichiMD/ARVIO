"use client";

import { useEffect, useRef, useState } from "react";
import { ArrowLeft, FolderOpen, RotateCw } from "lucide-react";
import type { CatalogConfig, MediaItem } from "@/lib/types";
import { useTranslation } from "@/lib/i18n";
import { LazyRail } from "./LazyRail";
import { RailScroller } from "./RailScroller";

export function CustomCollectionRail({ catalog, folders, onOpen }: {
  catalog: CatalogConfig; folders: CatalogConfig[]; onOpen: (item: MediaItem) => void;
}) {
  const t = useTranslation();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const selected = folders.find(f => f.id === selectedId);
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    if (selected) dialog.current?.showModal();
    else dialog.current?.close();
  }, [selected]);
  if (!folders.length) return null;
  return <section className="rail custom-collection-rail">
    <div className="rail-head"><h3>{catalog.name}</h3></div>
    <RailScroller className="rail-strip" ariaLabel={catalog.name}>
      {folders.map(folder => <CollectionTile key={`${folder.id}:${folder.collectionCoverImageUrl}`} folder={folder} onOpen={() => setSelectedId(folder.id)} />)}
    </RailScroller>
    <dialog ref={dialog} aria-label={selected?.name || catalog.name} className="custom-collection-dialog" onCancel={() => setSelectedId(null)} onClose={() => setSelectedId(null)}>
      <button type="button" className="icon-button" aria-label={t("Back")} title={t("Back")} onClick={() => setSelectedId(null)}><ArrowLeft size={20} /></button>
      {selected && <LazyRail key={`${selected.id}:${retry}`} catalog={selected} eager
        emptyContent={<div className="custom-collection-empty"><FolderOpen size={36} aria-hidden /><h3>{selected.name}</h3><p>{t("No titles found")}</p>
          <button type="button" className="icon-button" aria-label={t("Retry")} title={t("Retry")} onClick={() => setRetry(n => n + 1)}><RotateCw size={20} /></button></div>}
        onOpen={item => { setSelectedId(null); onOpen(item); }} />}
    </dialog>
  </section>;
}

function CollectionTile({ folder, onOpen }: { folder: CatalogConfig; onOpen: () => void }) {
  const [failed, setFailed] = useState(false);
  const artwork = !failed && folder.collectionCoverImageUrl;
  return <button type="button" className={`custom-collection-tile ${String(folder.collectionTileShape).toUpperCase() === "POSTER" ? "is-poster" : ""}`}
    aria-label={folder.name} title={folder.name} onClick={onOpen}
    onFocus={e => e.currentTarget.scrollIntoView({ block: "nearest", inline: "nearest" })}
    onKeyDown={e => {
      if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
      const next = e.key === "ArrowRight" ? e.currentTarget.nextElementSibling : e.currentTarget.previousElementSibling;
      if (next instanceof HTMLButtonElement) { e.preventDefault(); next.focus(); }
    }}>
    {artwork ? <img src={artwork} alt="" loading="lazy" onError={() => setFailed(true)} /> : <div className="custom-collection-art"><FolderOpen size={36} aria-hidden /></div>}
    {(!folder.collectionHideTitle || !artwork) && <span>{folder.name}</span>}
  </button>;
}
