import puppeteer from 'puppeteer';

(async () => {
    const browser = await puppeteer.launch({ args: ['--no-sandbox'] });
    const page = await browser.newPage();

    page.on('console', msg => console.log('BROWSER LOG:', msg.text()));
    page.on('pageerror', err => console.log('PAGE ERROR:', err.message));

    await page.goto('http://localhost:8080');

    // Wait for connection
    await new Promise(r => setTimeout(r, 2000));

    // click node
    await page.evaluate(() => {
        if (typeof openChart === "function") {
            openChart("Arb.Spread");
            console.log("Chart opened for Arb.Spread");
        } else {
            console.log("openChart is not defined!");
        }
    });

    await new Promise(r => setTimeout(r, 2000));
    await browser.close();
    console.log('Done');
})();
