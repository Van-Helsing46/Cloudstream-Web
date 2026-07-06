import { Route, Routes } from "react-router-dom";
import { HomePage } from "./pages/HomePage";
import { SearchPage } from "./pages/SearchPage";
import { DetailPage } from "./pages/DetailPage";
import { ExtensionsPage } from "./pages/ExtensionsPage";
import { LibraryPage } from "./pages/LibraryPage";
import { LoginGate } from "./pages/LoginGate";
import { ProfileGate } from "./pages/ProfileGate";
import { TopBar } from "./components/TopBar";
import { useT } from "./i18n";

export default function App() {
  return (
    <LoginGate>
      <ProfileGate>
        <Shell />
      </ProfileGate>
    </LoginGate>
  );
}

function Shell() {
  const t = useT();
  return (
    <>
      <TopBar />
      <main className="page">
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/search" element={<SearchPage />} />
          <Route path="/library" element={<LibraryPage />} />
          <Route path="/media/:providerId" element={<DetailPage />} />
          <Route path="/extensions" element={<ExtensionsPage />} />
          <Route path="*" element={<p className="muted">{t("notFound")}</p>} />
        </Routes>
      </main>
    </>
  );
}
