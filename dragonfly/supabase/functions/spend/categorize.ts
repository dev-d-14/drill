// Turns "250 chai", "uber 180", "1.2k zara", "rs 90 auto" into an amount, a note and a category.
export const CATEGORIES = ["Food", "Transport", "Shopping", "Bills", "Fun", "Gifts", "Health", "Study", "Other"];

const WORDS: Record<string, string[]> = {
  Food: ["chai", "tea", "coffee", "swiggy", "zomato", "food", "lunch", "dinner", "breakfast", "brunch", "snack", "snacks", "maggi", "biryani", "pizza", "burger", "dosa", "idli", "vada", "samosa", "juice", "shake", "starbucks", "cafe", "canteen", "mess", "restaurant", "hotel", "dominos", "kfc", "mcd", "mcdonalds", "subway", "icecream", "ice", "cream", "bakery", "cake", "pastry", "sweets", "chocolate", "chips", "coke", "pepsi", "water", "zepto", "blinkit", "instamart", "bigbasket", "dmart", "grocery", "groceries", "vegetables", "veggies", "fruits", "fruit", "milk", "eggs", "bread", "momos", "noodles", "pani", "puri", "chaat", "tiffin", "meal", "eat", "eating", "drinks", "boba", "chicken", "paneer", "shawarma", "frankie", "roll", "sandwich", "thali"],
  Transport: ["uber", "ola", "rapido", "auto", "cab", "metro", "bus", "train", "irctc", "petrol", "fuel", "diesel", "parking", "toll", "flight", "flights", "redbus", "bike", "scooty", "taxi", "fare", "travel", "ticket", "tickets", "airport", "namma", "yulu"],
  Shopping: ["amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "zara", "h&m", "hm", "clothes", "shoes", "dress", "kurti", "kurta", "top", "jeans", "bag", "makeup", "lipstick", "skincare", "cosmetics", "decathlon", "westside", "lifestyle", "pantaloons", "mall", "salon", "haircut", "nails", "earrings", "jewellery", "watch", "perfume", "shopping", "tshirt", "shirt", "saree", "sandals", "heels", "sunglasses", "phone", "case", "charger", "uniqlo", "trends", "shoppers"],
  Bills: ["rent", "electricity", "current", "wifi", "internet", "broadband", "recharge", "jio", "airtel", "vi", "bill", "bills", "gas", "emi", "insurance", "maintenance", "laundry", "maid", "subscription"],
  Fun: ["movie", "movies", "pvr", "inox", "bookmyshow", "netflix", "spotify", "prime", "hotstar", "jiocinema", "youtube", "game", "games", "party", "outing", "concert", "bowling", "trip", "pub", "bar", "club", "event", "show", "gaming", "arcade"],
  Gifts: ["gift", "gifts", "birthday", "bday", "present", "flowers", "bouquet", "donation", "treat", "anniversary"],
  Health: ["medicine", "medicines", "meds", "pharmacy", "apollo", "medplus", "doctor", "hospital", "clinic", "physio", "physiotherapy", "gym", "yoga", "tablets", "pills", "lab", "dental", "dentist", "eye", "glasses", "lens", "lenses", "checkup", "medical", "netmeds", "pharmeasy", "1mg", "protein", "vitamins"],
  Study: ["book", "books", "notes", "xerox", "printout", "print", "prints", "stationery", "pen", "pens", "course", "fees", "fee", "exam", "dgca", "coaching", "class", "classes", "tuition", "ground", "school", "sim", "simulator", "flying", "logbook", "headset", "charts", "jeppesen", "computer", "e6b", "plotter", "registration", "egca", "oxford", "license", "licence"],
};
const LOOKUP = new Map<string, string>();
for (const [cat, ws] of Object.entries(WORDS)) for (const w of ws) if (!LOOKUP.has(w)) LOOKUP.set(w, cat);

export function parseSpend(text: string): { amount: number; note: string } | null {
  const t = text.replace(/₹/g, " ").replace(/\b(rs|inr)\.?(?=\s|\d|$)/gi, " ").trim();
  const m = t.match(/(\d[\d,]*(?:\.\d+)?)\s*(k|K)?\b/);
  if (!m) return null;
  let amount = parseFloat(m[1].replace(/,/g, ""));
  if (m[2]) amount *= 1000;
  if (!isFinite(amount) || amount <= 0) return null;
  const note = (t.slice(0, m.index) + " " + t.slice((m.index ?? 0) + m[0].length)).replace(/\s+/g, " ").trim().toLowerCase();
  return { amount: Math.round(amount * 100) / 100, note: note || "spent" };
}

function words(note: string) {
  return note.toLowerCase().replace(/[^a-z0-9&\s]/g, " ").split(/\s+/).filter(Boolean);
}

export function categorize(note: string, learned: Record<string, string>): string | null {
  const ws = words(note);
  // her own corrections win
  for (const w of ws) if (learned[w]) return learned[w];
  // a whole-phrase lookup for two-word names
  for (let i = 0; i < ws.length - 1; i++) {
    const pair = ws[i] + ws[i + 1];
    if (LOOKUP.has(pair)) return LOOKUP.get(pair)!;
  }
  for (const w of ws) {
    if (LOOKUP.has(w)) return LOOKUP.get(w)!;
    // plural / typo-ish: "chais", "ubers", "moviee"
    const stem = w.replace(/(es|s|e)$/, "");
    if (stem.length > 2 && LOOKUP.has(stem)) return LOOKUP.get(stem)!;
    for (const [k, c] of LOOKUP) if (k.length >= 4 && (w.startsWith(k) || (w.length >= 4 && k.startsWith(w)))) return c;
  }
  return null;
}
