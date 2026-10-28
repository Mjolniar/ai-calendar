using System.Text.Json;

namespace AICalendar.Desktop.Tests;

/// <summary>A made-up kitchen, written as GET /v1/kitchen answers it.</summary>
internal static class KitchenSample
{
    /// <summary>Thursday, October 29, 2026, 5:30 PM: the third dinner of the week that started Tuesday.</summary>
    public static readonly DateTimeOffset Now = Sample.Local(2026, 10, 29, 17, 30);

    public static readonly DateOnly Today = new(2026, 10, 29);

    public static KitchenSnapshot Snapshot() => Read(Json);

    public static KitchenSnapshot Read(string json)
    {
        using var document = JsonDocument.Parse(json);
        return KitchenJson.ReadSnapshot(document.RootElement);
    }

    // Three open items to buy, two on an order, one bought this week and one bought too long ago to
    // show (the bridge would have left it out; the app filters it again). Eggs was bought nine days ago.
    public const string Json = """
    {
      "revision": 41,
      "list": [
        { "id": "kfoil0000001", "name": "Aluminum foil", "qty": "", "note": "", "status": "need", "orderRef": "",
          "source": "device", "addedBy": "Pixel 10 Pro XL", "addedAt": "2026-10-26T16:00:00.000Z", "updatedAt": "2026-10-26T16:00:00.000Z" },
        { "id": "koil00000002", "name": "Olive oil", "qty": "1 bottle", "note": "", "status": "need", "orderRef": "",
          "source": "pantry", "addedBy": "Pantry", "addedAt": "2026-10-27T18:00:00.000Z", "updatedAt": "2026-10-27T18:00:00.000Z" },
        { "id": "ktowel000003", "name": "Paper towels", "qty": "", "note": "the big pack", "status": "need", "orderRef": "",
          "source": "ai", "addedBy": "Claude", "addedAt": "2026-10-28T02:00:00.000Z", "updatedAt": "2026-10-28T02:00:00.000Z" },
        { "id": "ksalmon00004", "name": "Salmon fillets", "qty": "1 lb", "note": "", "status": "ordered",
          "orderRef": "Walmart #200000000001, Mon 11/2", "source": "ai", "addedBy": "Claude",
          "addedAt": "2026-10-26T12:00:00.000Z", "updatedAt": "2026-10-26T13:00:00.000Z" },
        { "id": "kpotato00005", "name": "Sweet potatoes", "qty": "3", "note": "", "status": "ordered",
          "orderRef": "Walmart #200000000001, Mon 11/2", "source": "ai", "addedBy": "Claude",
          "addedAt": "2026-10-26T12:01:00.000Z", "updatedAt": "2026-10-26T13:00:00.000Z" },
        { "id": "kmilk0000006", "name": "Milk", "qty": "1 gal", "note": "", "status": "got", "orderRef": "",
          "source": "device", "addedBy": "Pixel 10 Pro XL", "addedAt": "2026-10-25T15:00:00.000Z",
          "updatedAt": "2026-10-27T18:00:00.000Z", "doneAt": "2026-10-27T18:00:00.000Z" },
        { "id": "keggs0000007", "name": "Eggs", "qty": "18", "note": "", "status": "got", "orderRef": "",
          "source": "device", "addedBy": "Pixel 10 Pro XL", "addedAt": "2026-10-18T15:00:00.000Z",
          "updatedAt": "2026-10-20T18:00:00.000Z", "doneAt": "2026-10-20T18:00:00.000Z" }
      ],
      "pantry": [
        { "id": "pbeans000001", "name": "Black beans", "section": "Canned & jarred", "status": "have", "note": "", "basis": "bought 10/19", "checkedAt": "2026-10-26T18:00:00.000Z", "updatedAt": "2026-10-26T12:00:00.000Z" },
        { "id": "ptomato00002", "name": "Diced tomatoes", "section": "Canned & jarred", "status": "low", "note": "", "basis": "1 can left after Thu chili", "checkedAt": "2026-10-26T18:00:00.000Z", "updatedAt": "2026-10-26T12:00:00.000Z" },
        { "id": "pbroc0000003", "name": "Frozen broccoli", "section": "Freezer", "status": "have", "note": "", "basis": "bought 10/19; used Sun", "checkedAt": "2026-10-26T18:00:00.000Z", "updatedAt": "2026-10-26T12:00:00.000Z" },
        { "id": "pturkey00004", "name": "Ground turkey", "section": "Freezer", "status": "low", "note": "1 lb packs", "basis": "1 lb left after Thu chili", "checkedAt": "2026-10-26T18:00:00.000Z", "updatedAt": "2026-10-26T12:00:00.000Z" },
        { "id": "peggs0000005", "name": "Eggs", "section": "Fridge & counter", "status": "have", "note": "", "basis": "bought 10/20", "checkedAt": "2026-10-25T18:00:00.000Z", "updatedAt": "2026-10-20T18:00:00.000Z" },
        { "id": "poil00000006", "name": "Olive oil", "section": "Fridge & counter", "status": "out", "note": "", "basis": "Pixel 10 Pro XL said it's out", "checkedAt": "2026-10-24T18:00:00.000Z", "updatedAt": "2026-10-27T18:00:00.000Z" },
        { "id": "psalsa000007", "name": "Salsa", "section": "Sauces & condiments", "status": "low", "note": "", "basis": "used in Wed fajitas", "checkedAt": "2026-10-26T18:00:00.000Z", "updatedAt": "2026-10-26T12:00:00.000Z" },
        { "id": "psoy00000008", "name": "Low-sodium soy sauce", "section": "Sauces & condiments", "status": "have", "note": "", "basis": "bought 10/12, a big bottle", "checkedAt": "2026-10-26T18:00:00.000Z", "updatedAt": "2026-10-26T12:00:00.000Z" },
        { "id": "pcumin000009", "name": "Cumin", "section": "Spices", "status": "have", "note": "", "updatedAt": "2026-10-26T12:00:00.000Z" }
      ],
      "week": {
        "week": { "start": "2026-10-27", "end": "2026-11-02", "title": "Meal Plan · Oct 27–Nov 2, 2026" },
        "status": "ordered",
        "tagline": "For 2 · quick weeknights · make-ahead lunches",
        "meta": ["Ordered · delivery **Mon 4–6pm** · no substitutions"],
        "printNotes": "fields the apps do not use are ignored",
        "days": [
          { "date": "2026-10-28", "label": "Wed 10/28", "dinner": "Chicken fajita bowls", "base": "brown rice", "kind": "cook", "recipe": "fajitas" },
          { "date": "2026-10-27", "label": "Tue 10/27", "dinner": "Oven-baked salmon", "base": "sweet potato",
            "note": "Thaw 1 lb chicken tonight", "kind": "cook", "recipe": "salmon" },
          { "date": "2026-10-29", "label": "Thu 10/29", "dinner": "Turkey chili", "base": "cornbread muffins",
            "note": "Start it by **5:30** so it simmers an hour", "kind": "cook", "recipe": "chili" },
          { "date": "2026-10-30", "label": "Fri 10/30", "dinner": "Leftover turkey chili", "kind": "leftovers" },
          { "date": "2026-10-31", "label": "Sat 10/31", "dinner": "Sheet-pan lemon chicken", "base": "roasted potatoes", "kind": "cook", "recipe": "lemonchicken" },
          { "date": "2026-11-01", "label": "Sun 11/1", "dinner": "Beef and broccoli stir-fry", "base": "jasmine rice", "kind": "cook", "recipe": "stirfry" },
          { "date": "2026-11-02", "label": "Mon 11/2", "dinner": "Leftovers night", "kind": "leftovers" }
        ],
        "boxes": [ { "title": "Breakfast & lunch", "items": ["Overnight oats with berries", "Turkey and avocado wraps", "Greek yogurt and **granola**"] } ],
        "recipes": [
          { "id": "salmon", "title": "Oven-baked salmon", "sub": "Tue · 75 min · serves 2",
            "ingredients": "Skin-on salmon (~1 lb), olive oil, lemon, fresh dill, garlic powder, black pepper",
            "steps": ["Heat the smoker to 225°F.", "Pat the salmon dry, oil it and season it.", "Smoke skin side down about an hour, to 140°F inside."],
            "note": "Leftover salmon makes Wednesday's lunch." },
          { "id": "fajitas", "title": "Chicken fajita bowls", "sub": "Wed · 35 min · serves 2",
            "ingredients": "Chicken breast (1 lb), 2 bell peppers, 1 onion, fajita seasoning, brown rice, salsa",
            "steps": ["Cook the rice.", "Slice the chicken and vegetables.", "Sear the chicken, then the vegetables, with the seasoning.", "Serve over the rice with salsa."] },
          { "id": "chili", "title": "Turkey chili", "sub": "Thu · 1 hr 15 min · serves 4 (leftovers Friday)",
            "ingredients": "Ground turkey (1 lb), 1 onion, 2 cans **low-sodium** black beans, 1 can diced tomatoes, chili powder, cumin, cornbread mix",
            "steps": ["Brown the turkey with the onion.", "Add the beans, tomatoes and spices.", "Simmer for an hour, stirring now and then.",
                      "Bake the cornbread muffins while it simmers.", "Save half for Friday."],
            "note": "Half the beans can be mashed for a thicker chili." },
          { "id": "lemonchicken", "title": "Sheet-pan lemon chicken", "sub": "Sat · 50 min · serves 2",
            "ingredients": "Chicken thighs, baby potatoes, lemon, garlic, oregano", "steps": ["Toss everything with oil.", "Roast at 425°F for 40 minutes."] },
          { "id": "stirfry", "title": "Beef and broccoli", "sub": "Sun · 30 min · serves 2",
            "ingredients": "Flank steak, broccoli, low-sodium soy sauce, ginger, jasmine rice", "steps": "Stir-fry the beef, then the broccoli; sauce and serve over rice." }
        ]
      },
      "nextWeek": {
        "week": { "start": "2026-11-03", "end": "2026-11-09", "title": "Meal Plan · Nov 3–9, 2026" },
        "status": "draft",
        "tagline": "For 2 · quick weeknights · make-ahead lunches",
        "days": [
          { "date": "2026-11-03", "label": "Tue 11/3", "dinner": "Turkey meatballs", "base": "whole-wheat spaghetti", "recipe": "meatballs" },
          { "date": "2026-11-04", "label": "Wed 11/4", "dinner": "Black bean tacos", "base": "slaw" }
        ],
        "recipes": [ { "id": "meatballs", "title": "Turkey meatballs", "steps": ["Mix, roll and bake at 400°F for 20 minutes."] } ]
      },
      "corrections": [
        { "id": "cnote0000001", "kind": "note", "text": "Black beans: 2 cans left", "itemId": "pbeans000001", "itemName": "Black beans",
          "status": "open", "reply": "", "addedBy": "Pixel 10 Pro XL", "addedAt": "2026-10-29T15:00:00.000Z" },
        { "id": "cnote0000002", "kind": "note", "text": "Tossed the cilantro", "status": "open", "reply": "",
          "addedBy": "Pixel 10 Pro XL", "addedAt": "2026-10-29T20:00:00.000Z" },
        { "id": "cout00000003", "kind": "out", "text": "", "itemId": "poil00000006", "itemName": "Olive oil", "status": "applied",
          "reply": "Marked out and added to the shopping list.", "addedBy": "Pixel 10 Pro XL",
          "addedAt": "2026-10-27T18:00:00.000Z", "resolvedAt": "2026-10-27T18:00:00.000Z" },
        { "id": "cnote0000004", "kind": "note", "text": "We bought a big bag of rice", "status": "applied",
          "reply": "Added **Brown rice** to Dry goods as Have.", "addedBy": "Windows desktop (HOME-PC)",
          "addedAt": "2026-10-26T20:00:00.000Z", "resolvedAt": "2026-10-27T13:00:00.000Z" },
        { "id": "cnote0000005", "kind": "note", "text": "Is there any paprika?", "status": "dismissed",
          "reply": "None that I know of. Say if you want it on Monday's order.", "addedBy": "Pixel 10 Pro XL",
          "addedAt": "2026-10-25T18:00:00.000Z", "resolvedAt": "2026-10-26T13:00:00.000Z" },
        { "id": "cold00000006", "kind": "have", "text": "", "itemId": "peggs0000005", "itemName": "Eggs", "status": "applied",
          "reply": "Marked as on hand.", "addedBy": "Pixel 10 Pro XL",
          "addedAt": "2026-10-18T18:00:00.000Z", "resolvedAt": "2026-10-18T18:00:00.000Z" }
      ],
      "notes": {
        "2026-10-27": { "cooked": "yes", "rating": "loved", "note": "Less dill next time", "updatedAt": "2026-10-28T03:00:00.000Z" },
        "2026-10-28": { "cooked": "skipped", "rating": "", "note": "", "updatedAt": "2026-10-29T02:00:00.000Z" }
      }
    }
    """;
}
